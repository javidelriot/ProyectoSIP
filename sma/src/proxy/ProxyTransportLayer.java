package proxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import mensajesSIP.SIPMessage;

/**
 * Capa de transporte del proxy.
 * Se encarga solo de enviar y recibir datagramas UDP.
 */
public class ProxyTransportLayer {

    /** Tamaño del buffer de recepción. */
    private static final int BUFSIZE = 4 * 1024;

    private int listenPort;
    private DatagramSocket socket;
    private ProxyTransactionLayer transactionLayer;

    
    /** Activar logs completos de SIP (cabeceras). */
    private boolean debug = false;

    public void setDebug(boolean debug) {
        this.debug = debug;
    }
/**
     * Crea el socket UDP y lo deja escuchando en el puerto indicado.
     */
    public ProxyTransportLayer(int listenPort, ProxyTransactionLayer transactionLayer) throws SocketException {
        this.transactionLayer = transactionLayer;
        this.listenPort = listenPort;
        this.socket = new DatagramSocket(listenPort);
    }

    /**
     * Envía un mensaje SIP a la dirección y puerto indicados.
     */
    public void send(SIPMessage sipMessage, String address, int port) throws IOException {
        if (debug) {
           System.out.println("\n========== [PROXY SEND] -> " 
        	        + address + ":" + port + " ==========");
        	System.out.println(sipMessage.toStringMessage());
        	System.out.println("========== [END PROXY SEND] ==========\n");
        }
        byte[] bytes = sipMessage.toStringMessage().getBytes();
        sendSocket(bytes, address, port);
    }

    /**
     * Envío genérico de un datagrama UDP.
     */
    private void sendSocket(byte[] bytes, String address, int port) throws IOException {
        InetAddress inetAddress = InetAddress.getByName(address);
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, inetAddress, port);
        socket.send(packet);
    }

    /**
     * Bucle principal de escucha.
     * Recibe datagramas, los parsea a SIPMessage y se los pasa
     * a la capa de transacciones.
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

                String sourceIp = packet.getAddress().getHostAddress();
                int sourcePort = packet.getPort();

                
                if (debug) {
                    System.out.println("\n========== [PROXY RECV] <- " 
                	        + sourceIp + ":" + sourcePort + " ==========");
                	System.out.println(sipMessage.toStringMessage());
                	System.out.println("========== [END PROXY RECV] ==========\n");
                }

// Pasamos el mensaje a la capa de transacciones
                transactionLayer.onMessageReceived(sipMessage, sourceIp, sourcePort);

            } catch (Exception e) {
                System.err.println("Error en ProxyTransportLayer: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
