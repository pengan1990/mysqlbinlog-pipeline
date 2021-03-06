package com.jd.binlog.connection;

import com.jd.binlog.connection.packets.HeaderPacket;
import com.jd.binlog.connection.packets.client.ClientAuthenticationPacket;
import com.jd.binlog.connection.packets.server.ErrorPacket;
import com.jd.binlog.connection.packets.server.HandshakeInitializationPacket;
import com.jd.binlog.connection.packets.server.Reply323Packet;
import com.jd.binlog.connection.utils.MySQLPasswordEncrypter;
import com.jd.binlog.connection.utils.PacketManager;
import com.jd.binlog.server.BinlogPipelineConfig;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于mysql socket协议的链接实现
 * 
 * @author jianghang 2013-2-18 下午09:22:30
 * @version 1.0.1
 */
public class MysqlConnector {

    private static final Logger logger            = Logger.getLogger(MysqlConnector.class);
    private InetSocketAddress   address;
    private String              username;
    private String              password;

    private static byte                charsetNumber     = 33;
    private String defaultSchema;
    private int                 soTimeout         = 30 * 1000;
    private int                 receiveBufferSize = 16 * 1024;
    private int                 sendBufferSize    = 16 * 1024;

    private SocketChannel       channel;
    private AtomicBoolean       connected         = new AtomicBoolean(false);

    public MysqlConnector(BinlogPipelineConfig config){
        this(new InetSocketAddress(config.getMasterMySQLAddress(), config.getMasterMySQLPort()),
                config.getMasterMySQLUser(), config.getMasterMySQLPassword(),
                config.getDatabase());
    }

    public MysqlConnector(InetSocketAddress address, String username, String password, String databaseName){
        this(address, username, password, charsetNumber, databaseName);
    }

    public MysqlConnector(InetSocketAddress address, String username, String password, byte charsetNumber,
                          String defaultSchema){
        this.address = address;
        this.username = username;
        this.password = password;
        this.defaultSchema = defaultSchema;
        this.charsetNumber = charsetNumber;
    }

    public void connect() throws IOException {
        if (connected.compareAndSet(false, true)) {
            try {
                channel = SocketChannel.open();
                configChannel(channel);
                logger.info("connect MysqlConnection to " + address);
                channel.connect(address);
                negotiate(channel);
            } catch (Exception e) {
                disconnect();
                throw new IOException("connect " + this.address + " failure:" + ExceptionUtils.getStackTrace(e));
            }
        } else {
            logger.error("the channel can't be connected twice.");
        }
    }

    public void reconnect() throws IOException {
        disconnect();
        connect();
    }

    public void disconnect() throws IOException {
        if (connected.compareAndSet(true, false)) {
            try {
                if (channel != null) {
                    channel.close();
                }

                logger.info("disConnect MysqlConnection to " + address);
            } catch (Exception e) {
                throw new IOException("disconnect " + this.address + " failure:" + ExceptionUtils.getStackTrace(e));
            }
        } else {
            logger.info("the channel " + address  + " is not connected");
        }
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isConnected();
    }

    private void configChannel(SocketChannel channel) throws IOException {
        channel.socket().setKeepAlive(true);
        channel.socket().setReuseAddress(true);
        channel.socket().setSoTimeout(soTimeout);
        channel.socket().setTcpNoDelay(true);
        channel.socket().setReceiveBufferSize(receiveBufferSize);
        channel.socket().setSendBufferSize(sendBufferSize);
    }

    private void negotiate(SocketChannel channel) throws IOException {
        HeaderPacket header = PacketManager.readHeader(channel, 4);
        byte[] body = PacketManager.readBytes(channel, header.getPacketBodyLength());
        if (body[0] < 0) {// check field_count
            if (body[0] == -1) {
                ErrorPacket error = new ErrorPacket();
                error.fromBytes(body);
                throw new IOException("handshake exception:\n" + error.toString());
            } else if (body[0] == -2) {
                throw new IOException("Unexpected EOF packet at handshake phase.");
            } else {
                throw new IOException("unpexpected packet with field_count=" + body[0]);
            }
        }
        HandshakeInitializationPacket handshakePacket = new HandshakeInitializationPacket();
        handshakePacket.fromBytes(body);

        logger.info("handshake initialization packet received, prepare the client authentication packet to send");

        ClientAuthenticationPacket clientAuth = new ClientAuthenticationPacket();
        clientAuth.setCharsetNumber(charsetNumber);

        clientAuth.setUsername(username);
        clientAuth.setPassword(password);
        clientAuth.setServerCapabilities(handshakePacket.serverCapabilities);
        clientAuth.setDatabaseName(defaultSchema);
        clientAuth.setScrumbleBuff(joinAndCreateScrumbleBuff(handshakePacket));

        byte[] clientAuthPkgBody = clientAuth.toBytes();
        HeaderPacket h = new HeaderPacket();
        h.setPacketBodyLength(clientAuthPkgBody.length);
        h.setPacketSequenceNumber((byte) (header.getPacketSequenceNumber() + 1));

        PacketManager.write(channel,
            new ByteBuffer[] { ByteBuffer.wrap(h.toBytes()), ByteBuffer.wrap(clientAuthPkgBody) });
        logger.info("client authentication packet is sent out.");

        // check auth result
        header = PacketManager.readHeader(channel, 4);
        body = PacketManager.readBytes(channel, header.getPacketBodyLength());
        assert body != null;
        if (body[0] < 0) {
            if (body[0] == -1) {
                ErrorPacket err = new ErrorPacket();
                err.fromBytes(body);
                throw new IOException("Error When doing Client Authentication:" + err.toString());
            } else if (body[0] == -2) {
                auth323(channel, header.getPacketSequenceNumber(), handshakePacket.seed);
            } else {
                throw new IOException("unexpected packet with field_count=" + body[0]);
            }
        }
    }

    private void auth323(SocketChannel channel, byte packetSequenceNumber, byte[] seed) throws IOException {
        // auth 323
        Reply323Packet r323 = new Reply323Packet();
        if (password != null && password.length() > 0) {
            r323.seed = MySQLPasswordEncrypter.scramble323(password, new String(seed)).getBytes();
        }
        byte[] b323Body = r323.toBytes();

        HeaderPacket h323 = new HeaderPacket();
        h323.setPacketBodyLength(b323Body.length);
        h323.setPacketSequenceNumber((byte) (packetSequenceNumber + 1));

        PacketManager.write(channel, new ByteBuffer[] { ByteBuffer.wrap(h323.toBytes()), ByteBuffer.wrap(b323Body) });
        logger.info("client 323 authentication packet is sent out.");
        // check auth result
        HeaderPacket header = PacketManager.readHeader(channel, 4);
        byte[] body = PacketManager.readBytes(channel, header.getPacketBodyLength());
        assert body != null;
        switch (body[0]) {
            case 0:
                break;
            case -1:
                ErrorPacket err = new ErrorPacket();
                err.fromBytes(body);
                throw new IOException("Error When doing Client Authentication:" + err.toString());
            default:
                throw new IOException("unexpected packet with field_count=" + body[0]);
        }
    }

    private byte[] joinAndCreateScrumbleBuff(HandshakeInitializationPacket handshakePacket) throws IOException {
        byte[] dest = new byte[handshakePacket.seed.length + handshakePacket.restOfScrambleBuff.length];
        System.arraycopy(handshakePacket.seed, 0, dest, 0, handshakePacket.seed.length);
        System.arraycopy(handshakePacket.restOfScrambleBuff,
            0,
            dest,
            handshakePacket.seed.length,
            handshakePacket.restOfScrambleBuff.length);
        return dest;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public SocketChannel getChannel() {
        return channel;
    }
}
