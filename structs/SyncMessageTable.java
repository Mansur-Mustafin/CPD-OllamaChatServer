package structs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import server.client.RoomUser;

public class SyncMessageTable implements MessageTable {
    private final List<Message> messages;
    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;

    public SyncMessageTable() {
        this.messages = new ArrayList<>();

        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        this.readLock = lock.readLock();
        this.writeLock = lock.writeLock();
    }

    @Override
    public Message add(RoomUser user, String content) {
        int id = messages.size();
        Message message = new Message(id, user.getName(), content);

        writeLock.lock();
        try {
            messages.add(message);
        } finally {
            writeLock.unlock();
        }

        return message;
    }

    @Override
    public Optional<Message> get(int id) {
        Message message;

        readLock.lock();
        try {
            if (id >= 0 && id < messages.size())
                message = messages.get(id);
            else
                message = null;
        } finally {
            readLock.unlock();
        }

        return Optional.ofNullable(message);
    }

    @Override
    public List<Message> getAll() {
        List<Message> messages;

        readLock.lock();
        try {
            messages = Collections.unmodifiableList(this.messages);
        } finally {
            readLock.unlock();
        }

        return messages;
    }

    @Override
    public List<Message> getFrom(int id) {
        List<Message> messages;

        readLock.lock();
        try {
            if (id >= 0 && id < this.messages.size())
                messages = Collections.unmodifiableList(this.messages.subList(id, this.messages.size()));
            else
                messages = Collections.emptyList();
        } finally {
            readLock.unlock();
        }

        return messages;
    }

    @Override
    public List<Message> getLast(int count) {
        List<Message> messages;

        readLock.lock();
        try {
            if (this.messages.isEmpty()) {
                messages = Collections.emptyList();
            } else {
                int start = Math.max(0, this.messages.size() - Math.max(0, count));
                messages = Collections.unmodifiableList(this.messages.subList(start, this.messages.size()));
            }
        } finally {
            readLock.unlock();
        }

        return messages;
    }
}
