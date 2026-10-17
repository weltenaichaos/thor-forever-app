package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.xserver.events.SelectionClear;
import com.winlator.cmod.xserver.events.SelectionNotify;
import com.winlator.cmod.xserver.events.SelectionRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class SelectionManager implements XResourceManager.OnResourceLifecycleListener {
    private final SparseArray<Selection> selections = new SparseArray<>();

    /**
     * Owner ids for text this server holds itself (see [setServerText]): below every
     * client's ids (those start at 1 << 22) and far above the server's own few windows.
     * A new one for each new text, so a client that checks "who owns it now" sees a change.
     */
    private static final int SERVER_OWNER_BASE = 0x003F0000;
    private int serverOwnerSerial;

    public SelectionManager(WindowManager windowManager) {
        windowManager.addOnResourceLifecycleListener(this);
    }

    public static class Selection {
        public Window owner;
        private XClient client;
        /** Text the server itself holds (UTF-8), or null. */
        private byte[] serverText;
        private int serverOwnerId;
        /** Counts the server's texts; which one a client last asked the formats (TARGETS) or the text of. */
        public volatile int gen;
        public volatile int targetsGen;
        public volatile int textGen;
        /** The last thing a client fetched was the text itself, not the formats. */
        public volatile boolean textLast;
    }

    public void setSelection(int atom, Window owner, XClient client, int timestamp) {
        Selection selection = getSelection(atom);
        if (selection.owner != null && selection.client != null && (owner == null || selection.client != client)) {
            // The event names the window that lost it: clients check it against their own.
            selection.client.sendEvent(new SelectionClear(timestamp, selection.owner, atom));
        }
        selection.owner = owner;
        selection.client = client;
        selection.serverText = null;
        selection.serverOwnerId = 0;
    }

    /**
     * Makes this server the owner of [atom] holding [text], as if an X program had copied it:
     * a client that pastes from it (Wine, when the game pastes) gets the text through
     * ConvertSelection. A client that owned it is told it lost it. Returns whether a client
     * owned it. Call with the window manager locked.
     */
    public boolean setServerText(int atom, String text) {
        Selection selection = getSelection(atom);
        boolean fromClient = selection.owner != null && selection.client != null;
        if (fromClient) {
            selection.client.sendEvent(new SelectionClear((int)System.currentTimeMillis(), selection.owner, atom));
        }
        selection.owner = null;
        selection.client = null;
        selection.serverText = text.getBytes(StandardCharsets.UTF_8);
        serverOwnerSerial = (serverOwnerSerial + 1) & 0xFFFF;
        selection.serverOwnerId = SERVER_OWNER_BASE | serverOwnerSerial;
        selection.gen++;
        return fromClient;
    }

    /** The owner's window id for GetSelectionOwner (0: nobody). */
    public int ownerId(int atom) {
        Selection selection = getSelection(atom);
        if (selection.owner != null) return selection.owner.id;
        return selection.serverText != null ? selection.serverOwnerId : 0;
    }

    /**
     * ConvertSelection: a client owner is asked for it (SelectionRequest); text the server
     * holds goes straight into [property] on [requestor]; either way the requesting
     * [client] hears back with SelectionNotify (property 0: it can't be had).
     */
    public void convertSelection(XClient client, Window requestor, int atom, int target, int property, int timestamp) {
        Selection selection = getSelection(atom);
        if (selection.owner != null && selection.client != null) {
            selection.client.sendEvent(new SelectionRequest(timestamp, selection.owner.id, requestor.id, atom, target, property));
            return;
        }
        int answered = 0;
        if (selection.serverText != null) {
            answered = putServerText(selection, requestor, target, property != 0 ? property : target);
        }
        client.sendEvent(new SelectionNotify(timestamp, requestor.id, atom, target, answered));
    }

    /** Puts the server's text as [target] into [property] on [requestor]; returns the property, or 0. */
    private static int putServerText(Selection selection, Window requestor, int target, int property) {
        String name = Atom.getName(target);
        if (name == null) return 0;
        byte[] data;
        int type;
        Property.Format format = Property.Format.BYTE_ARRAY;
        switch (name) {
            case "TARGETS": {
                int[] targets = {target, Atom.internAtom("UTF8_STRING"), Atom.getId("STRING")};
                ByteBuffer buffer = ByteBuffer.allocate(targets.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int t : targets) buffer.putInt(t);
                data = buffer.array();
                type = Atom.getId("ATOM");
                format = Property.Format.INT_ARRAY;
                selection.targetsGen = selection.gen;
                selection.textLast = false;
                break;
            }
            case "UTF8_STRING":
            case "TEXT":
                data = selection.serverText.clone();
                type = Atom.internAtom("UTF8_STRING");
                selection.textGen = selection.gen;
                selection.textLast = true;
                break;
            case "STRING":
                data = new String(selection.serverText, StandardCharsets.UTF_8).getBytes(XServer.LATIN1_CHARSET);
                type = target;
                selection.textGen = selection.gen;
                selection.textLast = true;
                break;
            default:
                return 0;
        }
        requestor.replaceProperty(property, type, format, data);
        return property;
    }

    public Selection getSelection(int atom) {
        Selection selection = selections.get(atom);
        if (selection != null) return selection;
        selection = new Selection();
        selections.put(atom, selection);
        return selection;
    }

    @Override
    public void onFreeResource(XResource resource) {
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.valueAt(i);
            if (selection.owner == resource) selection.owner = null;
        }
    }
}
