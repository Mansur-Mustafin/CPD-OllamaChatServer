package structs;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import server.ClientThread;
import server.client.User;
import structs.security.PasswordHasher;
import structs.security.TokenManager;
import structs.storage.AuthFileStore;

public class SyncAuthDb implements AuthDb {
    private final Map<String, CredentialRecord> creds;
    private final AuthFileStore store;
    private final TokenManager tokenManager;
    private final PasswordHasher hasher;

    private final ReentrantLock lock;

    public SyncAuthDb(AuthFileStore store, TokenManager tokenManager, PasswordHasher hasher) throws IOException {
        this.lock = new ReentrantLock();

        this.store = store;
        this.creds = new HashMap<>(store.load());
        this.tokenManager = tokenManager;
        this.hasher = hasher;
    }

    @Override
    public Optional<User> register(String user, String pass, ClientThread thread) {
        lock.lock();

        try {
            if (creds.containsKey(user))
                return Optional.empty();

            CredentialRecord rec = hasher.hash(pass.toCharArray());
            creds.put(user, rec);

            try {
                store.append(user, rec);
            } catch (IOException ioe) {
                creds.remove(user);
                return Optional.empty();
            }

            String token = tokenManager.issue(user);
            return Optional.of(new User(thread, user, token));

        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<User> loginPass(String user, String pass, ClientThread thread) {
        lock.lock();

        try {
            CredentialRecord rec = creds.get(user);
            if (rec == null || !hasher.verify(pass.toCharArray(), rec))
                return Optional.empty();

            String token = tokenManager.issue(user);
            if (token == null)
                return Optional.empty();  // Token issuance failed
            return Optional.of(new User(thread, user, token));

        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<User> loginToken(String token, ClientThread thread) {
        lock.lock();

        try {
            Optional<String> validated = tokenManager.validate(token);
            if (validated.isEmpty())
                return Optional.empty();

            String newToken = tokenManager.issue(validated.get());
            return Optional.of(new User(thread, validated.get(), newToken));

        } finally {
            lock.unlock();
        }
    }
}
