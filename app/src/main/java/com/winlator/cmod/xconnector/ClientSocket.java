package com.winlator.cmod.xconnector;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public final int fd;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();
    // Data the client has not accepted yet. A busy or hung client (for example a frozen
    // Battle.net window) stops reading; blocking on it would stall the whole X server.
    private static final int MAX_PENDING_BYTES = 64 * 1024 * 1024;
    private final ArrayDeque<PendingData> pendingData = new ArrayDeque<>();
    private int pendingBytes = 0;
    private boolean overflowLogged = false;

    private static class PendingData {
        final ByteBuffer data;
        int ancillaryFd;

        PendingData(ByteBuffer data, int ancillaryFd) {
            this.data = data;
            this.ancillaryFd = ancillaryFd;
        }
    }

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public boolean hasAncillaryFds() {
        return !ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        return hasAncillaryFds() ? ancillaryFds.poll() : -1;
    }

    @Keep
    public void addAncillaryFd(int ancillaryFd) {
        ancillaryFds.add(ancillaryFd);
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        }
        else Log.d("ClientSocket", "Failed to write data.");
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = recvAncillaryMsg(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
        }
        else throw new IOException("Failed to send ancillary messages.");
    }

    /**
     * Sends data without ever waiting for the client. Whatever the client can't take now
     * is kept in order and sent later by {@link PendingDataFlusher}. The ancillary fd (or -1)
     * is sent with the first byte; the caller may close it as soon as this returns.
     */
    public synchronized void sendWithoutBlocking(ByteBuffer data, int ancillaryFd) {
        if (pendingData.isEmpty()) {
            int sent = trySend(data, ancillaryFd);
            if (sent < 0) return;
            if (sent > 0) ancillaryFd = -1;
            data.position(data.position() + sent);
            if (!data.hasRemaining()) return;
        }

        if (pendingBytes + data.remaining() > MAX_PENDING_BYTES) {
            if (!overflowLogged) Log.w("ClientSocket", "Client fd " + fd + " stopped reading; dropping its data.");
            overflowLogged = true;
            return;
        }

        ByteBuffer copy = ByteBuffer.allocateDirect(data.remaining());
        copy.put(data).flip();
        int ownFd = ancillaryFd >= 0 ? dupFd(ancillaryFd) : -1;
        pendingData.add(new PendingData(copy, ownFd));
        pendingBytes += copy.remaining();
        PendingDataFlusher.schedule(this);
    }

    /** Sends as much pending data as the client takes. Returns true when nothing is left. */
    synchronized boolean flushPendingData() {
        while (!pendingData.isEmpty()) {
            PendingData pending = pendingData.peek();
            int sent = trySend(pending.data, pending.ancillaryFd);
            if (sent < 0) return true;
            if (sent == 0) return false;
            if (pending.ancillaryFd >= 0) {
                XConnectorEpoll.closeFd(pending.ancillaryFd);
                pending.ancillaryFd = -1;
            }
            pending.data.position(pending.data.position() + sent);
            pendingBytes -= sent;
            if (pending.data.hasRemaining()) return false;
            pendingData.poll();
        }
        return true;
    }

    /** Forgets unsent data, e.g. when the connection closes, so it never reaches a reused fd. */
    public synchronized void discardPendingData() {
        for (PendingData pending : pendingData) {
            if (pending.ancillaryFd >= 0) XConnectorEpoll.closeFd(pending.ancillaryFd);
        }
        pendingData.clear();
        pendingBytes = 0;
    }

    // Returns the bytes sent, 0 if the client can't take any now, or -1 after an error
    // (pending data is then discarded, as the old blocking write also gave up on errors).
    private int trySend(ByteBuffer data, int ancillaryFd) {
        int sent = sendNonBlocking(fd, data, data.position(), data.remaining(), ancillaryFd);
        if (sent < 0) {
            Log.d("ClientSocket", "Failed to write data.");
            discardPendingData();
        }
        return sent;
    }

    private native int sendNonBlocking(int fd, ByteBuffer data, int offset, int length, int ancillaryFd);

    private static native int dupFd(int fd);

    private native int read(int fd, ByteBuffer data, int offset, int length);

    private native int write(int fd, ByteBuffer data, int length);

    private native int recvAncillaryMsg(int clientFd, ByteBuffer data, int offset, int length);

    private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
