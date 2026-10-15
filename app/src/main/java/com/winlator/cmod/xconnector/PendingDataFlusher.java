package com.winlator.cmod.xconnector;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Keeps retrying data that X clients could not take yet, on one background thread. */
final class PendingDataFlusher {
    private static final long RETRY_INTERVAL_MS = 2;
    private static final Object lock = new Object();
    private static final LinkedHashSet<ClientSocket> sockets = new LinkedHashSet<>();
    private static Thread thread;

    private PendingDataFlusher() {}

    // Called with the socket's monitor held; lock order is always socket, then this lock.
    static void schedule(ClientSocket socket) {
        synchronized (lock) {
            sockets.add(socket);
            if (thread == null) {
                thread = new Thread(PendingDataFlusher::run, "XClientFlush");
                thread.setDaemon(true);
                thread.start();
            }
            else lock.notify();
        }
    }

    private static void run() {
        ArrayList<ClientSocket> snapshot = new ArrayList<>();
        while (true) {
            synchronized (lock) {
                while (sockets.isEmpty()) {
                    try {
                        lock.wait();
                    }
                    catch (InterruptedException e) {}
                }
                snapshot.clear();
                snapshot.addAll(sockets);
            }

            for (ClientSocket socket : snapshot) {
                synchronized (socket) {
                    if (socket.flushPendingData()) {
                        synchronized (lock) {
                            sockets.remove(socket);
                        }
                    }
                }
            }

            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            }
            catch (InterruptedException e) {}
        }
    }
}
