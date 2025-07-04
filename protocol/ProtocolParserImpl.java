package protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import protocol.unit.TokenLoginUnit;
import protocol.unit.EnterUnit;
import protocol.unit.EofUnit;
import protocol.unit.ErrUnit;
import protocol.unit.InvalidUnit;
import protocol.unit.LeaveUnit;
import protocol.unit.ListRoomsUnit;
import protocol.unit.LoginUnit;
import protocol.unit.LogoutUnit;
import protocol.unit.OkUnit;
import protocol.unit.PingUnit;
import protocol.unit.PongUnit;
import protocol.unit.ProtocolUnit;
import protocol.unit.RegisterUnit;
import protocol.unit.SendUnit;
import protocol.unit.SyncUnit;
import protocol.unit.RecvUnit;

@FunctionalInterface
interface ParseHandler {
    ProtocolUnit apply(List<String> args);
}

public class ProtocolParserImpl implements ProtocolParser {
    private final Map<String, ParseHandler> handlerMap;

    public ProtocolParserImpl() {
        this.handlerMap = Map.ofEntries(
                Map.entry("login-token", this::buildAuthToken),
                Map.entry("login", this::buildLogin),
                Map.entry("register", this::buildRegister),
                Map.entry("logout", this::buildLogout),
                Map.entry("list-rooms", this::buildListRooms),
                Map.entry("enter", this::buildEnter),
                Map.entry("leave", this::buildLeave),
                Map.entry("send", this::buildSend),
                Map.entry("recv", this::buildRecv),
                Map.entry("sync", this::buildSync),
                Map.entry("ok", this::buildOk),
                Map.entry("err", this::buildErr),
                Map.entry("ping", this::buildPing),
                Map.entry("pong", this::buildPong));
    }

    @Override
    public ProtocolUnit parse(String string) {
        if (string == null || string.isEmpty())
            return new EofUnit();

        string = string.strip();

        int firstSpaceIndex = string.indexOf(' ');
        if (firstSpaceIndex == -1)
            firstSpaceIndex = string.length();

        String command = string.substring(0, firstSpaceIndex);
        ParseHandler handler = handlerMap.get(command);
        if (handler == null)
            return new InvalidUnit();

        String argString = string.substring(firstSpaceIndex).strip();
        List<String> args = ProtocolUtils.tokenize(argString);
        return handler.apply(args);
    }

    private ProtocolUnit buildLogin(List<String> args) {
        if (args.size() != 2)
            return new InvalidUnit();

        String user = args.get(0);
        String pass = args.get(1);

        return new LoginUnit(user, pass);
    }

    private ProtocolUnit buildRegister(List<String> args) {
        if (args.size() != 2)
            return new InvalidUnit();

        String user = args.get(0);
        String pass = args.get(1);

        return new RegisterUnit(user, pass);
    }

    private ProtocolUnit buildLogout(List<String> args) {
        if (args.size() != 0)
            return new InvalidUnit();

        return new LogoutUnit();
    }

    private ProtocolUnit buildListRooms(List<String> args) {
        if (args.size() != 0)
            return new InvalidUnit();

        return new ListRoomsUnit();
    }

    private ProtocolUnit buildEnter(List<String> args) {
        if (args.size() != 1)
            return new InvalidUnit();

        String roomName = args.get(0);

        return new EnterUnit(roomName);
    }

    private ProtocolUnit buildLeave(List<String> args) {
        if (args.size() != 0)
            return new InvalidUnit();

        return new LeaveUnit();
    }

    private ProtocolUnit buildSend(List<String> args) {
        if (args.size() != 1)
            return new InvalidUnit();

        String message = args.get(0);

        return new SendUnit(message);
    }

    private ProtocolUnit buildRecv(List<String> args) {
        if (args.size() != 3)
            return new InvalidUnit();

        Integer id = parseInt(args.get(0));
        if (id == null || id < 0)
            return new InvalidUnit();

        String username = args.get(1);
        String message = args.get(2);

        return new RecvUnit(id, username, message);
    }

    private ProtocolUnit buildSync(List<String> args) {
        if (args.size() != 1)
            return new InvalidUnit();

        Integer id = parseInt(args.get(0));
        if (id == null || id < 0)
            return new InvalidUnit();

        return new SyncUnit(id);
    }

    private ProtocolUnit buildOk(List<String> args) {
        if (args.size() < 1 || args.size() > 2)
            return new InvalidUnit();

        Optional<ProtocolOkIdentifier> id = ProtocolOkIdentifier.fromString(args.get(0));
        if (id.isEmpty())
            return new InvalidUnit();

        String data = args.size() == 2 ? args.get(1) : "";

        return new OkUnit(id.get(), data);
    }

    private ProtocolUnit buildErr(List<String> args) {
        if (args.size() != 1)
            return new InvalidUnit();

        Optional<ProtocolErrorIdentifier> id = ProtocolErrorIdentifier.fromString(args.get(0));
        if (id.isEmpty())
            return new InvalidUnit();

        return new ErrUnit(id.get());
    }

    private ProtocolUnit buildAuthToken(List<String> args) {
        if (args.size() != 1)
            return new InvalidUnit();

        String token = args.get(0);

        return new TokenLoginUnit(token);
    }

    private ProtocolUnit buildPing(List<String> args) {
        if (args.size() != 0)
            return new InvalidUnit();

        return new PingUnit();
    }

    private ProtocolUnit buildPong(List<String> args) {
        if (args.size() != 0)
            return new InvalidUnit();

        return new PongUnit();
    }

    private Integer parseInt(String str) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
