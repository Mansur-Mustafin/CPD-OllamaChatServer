package client;

import client.state.AuthState;
import client.state.ClientState;
import client.state.DeadState;
import client.state.GuestState;
import client.state.InteractiveState;
import client.state.NonInteractiveState;
import client.state.SynchronizableState;
import client.storage.SessionStore;
import exception.EndpointUnreachableException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import protocol.ProtocolParser;
import protocol.ProtocolParserImpl;
import protocol.ProtocolPort;
import protocol.SocketProtocolPort;
import protocol.unit.EofUnit;
import protocol.unit.ListRoomsUnit;
import protocol.unit.ProtocolUnit;
import utils.ConfigUtils;
import utils.SocketUtils;

public abstract class BaseClient {
    private static final String SESSION_PATH_FORMAT = "session%s.properties";
    private static final String CONFIG_PATH = "client.properties";
    private static final int INPUT_DELAY = 100;

    private final ProtocolPort port;
    private ClientState state;
    private final ProtocolParser parser;
    private final SessionStore session;

    private boolean done;
    private boolean seenRooms;
    private final ReentrantLock stateUpdateLock;
    private final Condition stateUpdateCondition;

    public BaseClient(ProtocolPort port, ProtocolParser parser, SessionStore session) {
        this.port = port;
        this.parser = parser;
        this.session = session;

        this.state = new DeadState(this);

        this.done = false;
        this.seenRooms = false;
        this.stateUpdateLock = new ReentrantLock();
        this.stateUpdateCondition = stateUpdateLock.newCondition();
    }

    protected abstract ClientState getInitialState();

    public ClientState getState() {
        return state;
    }

    public SessionStore getSession() {
        return session;
    }

    public ProtocolParser getParser() {
        return parser;
    }

    public ProtocolPort getPort() {
        return port;
    }

    public void setState(ClientState state) {
        this.state = state;

        stateUpdateLock.lock();
        try {
            stateUpdateCondition.signalAll();
        } finally {
            stateUpdateLock.unlock();
        }

        if (state instanceof SynchronizableState syncState && syncState.getSyncId() != -1) {
            try {
                port.send(syncState.getSyncUnit());
            } catch (IOException e) {
                Cli.printError("Failed to synchronize state");
            }
        }

        if (state instanceof AuthState && !seenRooms) {
            seenRooms = true;
            ProtocolUnit listRoomsUnit = new ListRoomsUnit();
            try {
                port.send(listRoomsUnit);
            } catch (IOException e) {
                Cli.printError("Failed to synchronize state");
            }
        }

        if (state instanceof GuestState)
            seenRooms = false;
    }

    public void waitForStateUpdate() throws InterruptedException {
        stateUpdateLock.lock();
        try {
            stateUpdateCondition.await();
        } finally {
            stateUpdateLock.unlock();
        }
    }

    public void run() {
        // Ensure cleanup is executed on shutdown
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(this::cleanup));

        Thread sending = Thread.ofVirtual().unstarted(this::handleSending);
        Thread receiving = Thread.ofVirtual().unstarted(this::handleReceiving);

        sending.start();
        receiving.start();

        try {
            sending.join();
            receiving.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleSending() {
        try {
            while (!done) {
                if (state instanceof InteractiveState intState) {
                    String input = Cli.getInput();
                    if (input == null) {
                        Thread.sleep(INPUT_DELAY); // Wait a bit for some input
                        continue;
                        // This prevents lack of input from blocking state updates
                    }

                    // State updates can be triggered while waiting for input
                    if (state != intState) {
                        if (state instanceof InteractiveState newState) {
                            intState = newState;
                        } else {
                            continue;
                        }
                    }

                    Optional<ProtocolUnit> unit = intState.buildNextUnit(input);
                    if (unit.isEmpty())
                        continue;

                    try {
                        port.send(unit.get());
                    } catch (IOException e) {
                        Cli.printError("Failed to send message: " + e.getMessage());
                        continue;
                    }

                } else if (state instanceof NonInteractiveState nonInteractiveState) {
                    Optional<ProtocolUnit> unit = nonInteractiveState.buildNextUnit();
                    if (unit.isPresent()) {
                        try {
                            port.send(unit.get());
                        } catch (IOException e) {
                            Cli.printError("Failed to send message: " + e.getMessage());
                            continue;
                        }
                    } else {
                        try {
                            waitForStateUpdate();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                } else {
                    try {
                        waitForStateUpdate();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    private void handleReceiving() {
        setState(getInitialState());

        try {
            while (!done) {
                ProtocolUnit unit = port.receive();
                if (unit instanceof EofUnit) {
                    if (done) // Connection closed by client
                        return;

                    port.connect();
                    setState(getInitialState());

                    continue;
                }

                Optional<ProtocolUnit> response = unit.accept(state);
                if (response.isPresent()) {
                    port.send(response.get());
                }
            }
        } catch (EndpointUnreachableException e) {
            Cli.printError("Connection to server lost, terminating.");
        } catch (IOException e) {
            Cli.printError("Unexpected error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    public void cleanup() {
        if (done)
            return;

        done = true;
        setState(new DeadState(this));

        session.setLocked(false);
        try {
            session.save();
        } catch (IOException e) {
            Cli.printError("Failed to save session: " + e.getMessage());
        }

        try {
            port.close();
        } catch (IOException e) {
            Cli.printError("Failed to close port: " + e.getMessage());
        }
    }

    private static Socket createSocket(InetAddress address, int port, String password, String truststorePath) {
        try {
            Socket socket = SocketUtils.newSSLSocket(address, port, password, truststorePath);
            SocketUtils.configureSocket(socket);
            Cli.printConnection("Socket port: " + socket.getLocalPort());

            return socket;
        } catch (IOException e) {
            return null;
        }
    }

    protected static Optional<ProtocolPort> getProtocolPort() {
        Properties config;

        try {
            config = ConfigUtils.loadConfig(CONFIG_PATH);
        } catch (IOException e) {
            Cli.printError("Failed to load config: " + e.getMessage());
            return Optional.empty();
        }

        List<String> missingKeys = ConfigUtils.getMissing(config,
                List.of("host", "port", "truststore-password", "truststore"));
        if (!missingKeys.isEmpty()) {
            Cli.printError("Missing configuration keys: " + missingKeys);
            return Optional.empty();
        }

        String host = config.getProperty("host");
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (IOException e) {
            Cli.printError("Invalid host name: " + host);
            return Optional.empty();
        }

        int port = ConfigUtils.getIntProperty(config, "port");
        if (port < 1024 || port > 65535) {
            Cli.printError("Port number must be between 1024 and 65535, port " + port + " provided.");
            return Optional.empty();
        }

        String truststorePath = config.getProperty("truststore");
        String password = config.getProperty("truststore-password");

        ProtocolParser parser = new ProtocolParserImpl();
        Supplier<Socket> socketFactory = () -> createSocket(address, port, password, truststorePath);
        ProtocolPort protocolPort = new SocketProtocolPort(socketFactory, parser);

        try {
            protocolPort.connect();
        } catch (IOException | EndpointUnreachableException e) {
            Cli.printError("Failed to connect to server at " + host + ":" + port + ": " + e.getMessage());
            return Optional.empty();
        }

        return Optional.of(protocolPort);
    }

    protected static Optional<SessionStore> loadSession(String sessionSuffix) {
        String sessionPath = String.format(SESSION_PATH_FORMAT, sessionSuffix);
        SessionStore sessionStore;

        try {
            sessionStore = new SessionStore(sessionPath);
        } catch (IOException e) {
            Cli.printError("Failed to load session: " + e.getMessage());
            return Optional.empty();
        }

        if (sessionStore.isLocked()) {
            Cli.printWarning("If you got this message right after launching the client, it means you are trying to use the same session in two clients. Try changing the session suffix in the command-line.");
            return Optional.of(new SessionStore());
        }

        sessionStore.setLocked(true);
        try {
            sessionStore.save();
        } catch (IOException e) {
            Cli.printError("Failed to save session: " + e.getMessage());
            return Optional.empty();
        }

        return Optional.of(sessionStore);
    }
}
