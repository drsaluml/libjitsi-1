package org.jitsi.examples.PacketPlayer;

import java.net.DatagramPacket;
import java.net.InetAddress;

public class RtpData
{
    public RtpData(DatagramPacket p)
    {
        data = p.getData();
        offset = p.getOffset();
    }

    public RtpData()
    {
        data = new byte[2048];
        offset = 0;
    }

    public boolean isRtcp()
    {
        return pt >= 72 && pt <= 76;
    }

    @Override
    public String toString()
    {
        return "RtpData[length:" + payloadLength +
                ",protocol:" + protocol +
                ",pt:" + pt +
                ",ssrc:" + ssrc +
                ",src:" + srcIp + ":" + srcPort +
                ",dst:" + dstIp + ":" + dstPort +
                "]";
    }

    int offset;
    long timestamp;
    byte[] data;
    int payloadLength;
    int ssrc;
    byte pt;
    int protocol;
    InetAddress srcIp;
    int srcPort;
    InetAddress dstIp;
    int dstPort;
}