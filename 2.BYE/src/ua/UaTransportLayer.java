package ua;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import mensajesSIP.SIPMessage;

/**
 * Capa de transporte del UA.
 * Se encarga de enviar y recibir datagramas UDP.
 */
public class UaTransportLayer {

    /** Tamaño del buffer de recepción. */
    private static final int BUFSIZE = 4 * 1024;

    private int listenPort;
    private String proxyAddress;
    private int proxyPort;
    private DatagramSocket socket;
    private UaTransactionLayer transactionLayer;

    /**
     * Constructor.
     * Crea el socket UDP en el puerto indicado y guarda la info del proxy.
     */
    public UaTransportLayer(int listenPort,
                            String proxyAddress,
                            int proxyPort,
                            UaTransactionLayer transactionLayer) throws SocketException {

        this.transactionLayer = transactionLayer;
        this.listenPort = listenPort;
        this.proxyAddress = proxyAddress;
        this.proxyPort = proxyPort;
        this.socket = new DatagramSocket(listenPort);
    }

    /**
     * Envía un mensaje SIP directamente al proxy usando la IP y puerto configurados.
     */
    public void sendToProxy(SIPMessage sipMessage) throws IOException {
        byte[] data = sipMessage.toStringMessage().getBytes();
        send(data, this.proxyAddress, this.proxyPort);
    }

    /**
     * Envía un mensaje SIP a una dirección y puerto concretos.
     * (Por si en algún momento se quiere enviar a otro UA).
     */
    public void send(SIPMessage sipMessage, String address, int port) throws IOException {
        byte[] data = sipMessage.toStringMessage().getBytes();
        send(data, address, port);
    }

    /**
     * Envío genérico de un datagrama UDP.
     */
    private void send(byte[] bytes, String address, int port) throws IOException {
        InetAddress inetAddress = InetAddress.getByName(address);
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, inetAddress, port);
        socket.send(packet);
    }

    /**
     * Bucle principal de escucha.
     * Recibe datagramas, los parsea a SIPMessage y los pasa
     * a la capa de transacción del UA.
     */
    public void startListening() {
        System.out.println("Listening at " + listenPort + "...");
        while (true) {
            try {
                byte[] buf = new byte[BUFSIZE];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                // Espera bloqueante a que llegue un datagrama
                socket.receive(packet);

                // Construimos el String solo con la parte útil del buffer
                String msg = new String(packet.getData(), 0, packet.getLength());

                SIPMessage sipMessage = SIPMessage.parseMessage(msg);

                // Pasamos el mensaje a la capa de transacciones
                transactionLayer.onMessageReceived(sipMessage);

            } catch (Exception e) {
                System.err.println("Error en UaTransportLayer: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
