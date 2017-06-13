package com.mrd.bitlib.model;

/**
 * Created by User on 5/13/2017.
 */

import java.io.Serializable;

public class ScriptOutputReturn extends ScriptOutput implements Serializable {
    private static final long serialVersionUID = 1L;

    private byte[] _returnBytes;

    protected ScriptOutputReturn(byte[][] chunks, byte[] scriptBytes) {
        super(scriptBytes);
        _returnBytes = chunks[2];
    }

    protected static boolean isScriptOutputReturn(byte[][] chunks) {

        if (!Script.isOP(chunks[0], 0x6a)) {
            return false;
        }
        return true;
    }

    public ScriptOutputReturn(byte[] returnBytes) {
        //todo check length for type specfic length 20?
        super(scriptEncodeChunks(new byte[][] { { (byte) 0x6a}, returnBytes,
               }));
        _returnBytes = returnBytes;
    }



    /**
     * Get the address that this output is for.
     *
     * @return The address that this output is for.
     */
    public byte[] getAddressBytes() {
        return _returnBytes;
    }

    @Override
    public Address getAddress(NetworkParameters network) {
        return Address.fromStandardBytes(getAddressBytes(), network);
    }

}

