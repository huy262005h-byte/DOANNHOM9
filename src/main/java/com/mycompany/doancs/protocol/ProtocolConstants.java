package com.mycompany.doancs.protocol;

public final class ProtocolConstants {
    private ProtocolConstants() {}

    public static final int DEFAULT_PORT = 8888;
    public static final int BUFFER_SIZE = 64 * 1024; // 64 KB chunk size for fast streaming
    public static final int MAGIC_NUMBER = 0x53594E43; // 'SYNC'
    public static final byte PROTOCOL_VERSION = 1;

    // Command Codes (Client -> Server)
    public static final byte CMD_PING = 0x01;
    public static final byte CMD_GET_FILE_LIST = 0x02;
    public static final byte CMD_UPLOAD_FILE = 0x03;
    public static final byte CMD_DOWNLOAD_FILE = 0x04;
    public static final byte CMD_DELETE_FILE = 0x05;
    public static final byte CMD_DISCONNECT = 0x09;

    // Response Codes (Server -> Client)
    public static final byte RESP_OK = (byte) 0x80;
    public static final byte RESP_PONG = (byte) 0x81;
    public static final byte RESP_FILE_LIST = (byte) 0x82;
    public static final byte RESP_READY = (byte) 0x83;
    public static final byte RESP_DOWNLOAD_OK = (byte) 0x84;
    public static final byte RESP_FILE_NOT_FOUND = (byte) 0x8E;
    public static final byte RESP_ERROR = (byte) 0x8F;
}
