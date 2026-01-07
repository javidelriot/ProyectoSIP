package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.ByeMessage;

/**
 * Capa de transacciones del Proxy.
 *
 * - Recibe TODOS los mensajes que llegan al Proxy desde la red
 * - Decide qué hacer con cada uno en función del tipo (REGISTER, INVITE, 180, 200, ACK...).
 * - Controla un estado muy simple de ocupación del proxy (IDLE/BUSY) para
 *   limitarlo a UNA llamada simultánea.
 * - Pide a {@link ProxyUserLayer} que haga la lógica de "negocio":
 *   mirar registros, decidir a dónde reenviar, etc.
 */

public class ProxyTransactionLayer {

    /** Proxy libre, sin llamada en curso. */
    private static final int IDLE = 0;

    /** Proxy ocupado gestionando una llamada (un Call-ID activo). */
    private static final int BUSY = 1;

    /** Estado actual del proxy: IDLE/BUSY. */
    private int state = IDLE;

    /** Call-ID de la llamada actualmente atendida por el proxy (si está BUSY). */
    private String activeCallId = null;

    private final ProxyUserLayer userLayer;
    private final ProxyTransportLayer transportLayer;


    public ProxyTransactionLayer(int listenPort, ProxyUserLayer userLayer) throws SocketException {
        this.userLayer = userLayer;
        this.transportLayer = new ProxyTransportLayer(listenPort, this);
    }



    /**
     * Punto central de entrada de TODOS los mensajes que llegan al proxy.
     * Este método es invocado por {ProxyTransportLayer} cada vez
     * que se recibe un datagrama UDP con un mensaje SIP.
     *
     * @param sipMessage mensaje SIP ya parseado.
     * @param sourceIp   IP origen del datagrama.
     * @param sourcePort puerto origen del datagrama.
     */
    public void onMessageReceived(SIPMessage sipMessage, String sourceIp, int sourcePort) throws IOException {

        // 1) REGISTER → lo delegamos a la userLayer (gestión de tabla de registros)
        if (sipMessage instanceof RegisterMessage) {
            userLayer.onRegisterReceived((RegisterMessage) sipMessage);
            return;
        }

        // 2) INVITE → inicio de una llamada
        if (sipMessage instanceof InviteMessage) {
            handleInvite((InviteMessage) sipMessage, sourceIp, sourcePort);
            return;
        }

        // 3) 180 Ringing (desde el callee hacia el proxy)
        if (sipMessage instanceof RingingMessage) {
            userLayer.onRingingFromCallee((RingingMessage) sipMessage);
            return;
        }

        // 4) 200 OK (puede ser respuesta a INVITE, BYE, etc.)
        if (sipMessage instanceof OKMessage) {
            OKMessage ok = (OKMessage) sipMessage;
            String cseqMethod = ok.getcSeqStr();

            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                userLayer.onInviteOKFromCallee(ok);
                return;
            }

            if ("BYE".equalsIgnoreCase(cseqMethod)) {
                userLayer.onByeOKFromCallee(ok);
                return;
            }
        }

        

        // 5) 404 Not Found (por ahora solo informativo, ya se ha reenviado antes)
        if (sipMessage instanceof NotFoundMessage) {
            NotFoundMessage nf = (NotFoundMessage) sipMessage;
            String cseqMethod = nf.getcSeqStr();
            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                System.out.println("[Proxy-TX] 404 Not Found para INVITE → ya reenviado al llamante.");
                return;
            }
        }

        // 6) ACK (desde el caller hacia el proxy)
        if (sipMessage instanceof ACKMessage) {
            handleAck((ACKMessage) sipMessage);
            return;
        }

        // 7) 486 Busy Here (rechazo del callee)
        if (sipMessage instanceof BusyHereMessage) {
            userLayer.onBusyHereFromCallee((BusyHereMessage) sipMessage);
            return;
        }

        // 8) 408 Request Timeout (el callee no ha contestado en 10 s)
        if (sipMessage instanceof RequestTimeoutMessage) {
            userLayer.onRequestTimeoutFromCallee((RequestTimeoutMessage) sipMessage);
            return;
        }
        
        // 9) BYE: pasa a la capa de usuario para reenviarlo
        if (sipMessage instanceof ByeMessage) {
            ByeMessage bye = (ByeMessage) sipMessage;
            userLayer.onByeReceived(bye);
            return;
        }
        
        


        // Cualquier otro mensaje no previsto
        System.err.println("[Proxy-TX] Mensaje inesperado de tipo "
                + sipMessage.getClass().getSimpleName() + " → se ignora.");
    }

    // ========================================================================
    //   LÓGICA DE INVITE
    // ========================================================================

    /**
     * Gestiona un INVITE recibido por el proxy.
     * 
     * Si el proxy está IDLE, pasa a BUSY, guarda el Call-ID y delega en
     *       {@link ProxyUserLayer#onInviteReceived} la lógica de reenvío.
     *       
     *Si el proxy está BUSY, responde 503 Service Unavailable al llamante
     *
     * @param invite    INVITE recibido.
     * @param sourceIp  IP desde la que se ha recibido el INVITE (llamante).
     * @param sourcePort puerto UDP del llamante.
     */
    private void handleInvite(InviteMessage invite, String sourceIp, int sourcePort) throws IOException {

        String callId = invite.getCallId();

        if (state == IDLE) {
            // Primera llamada: el proxy pasa a BUSY con este Call-ID
            state = BUSY;
            activeCallId = callId;

            System.out.println("[Proxy-TX] Nuevo INVITE (Call-ID=" + callId + ") → Proxy pasa a BUSY.");

            // El resto de lógica (100 Trying, comprobación de registros,
            // reenvío al callee, etc.) se hace en ProxyUserLayer.
            userLayer.onInviteReceived(invite, sourceIp, sourcePort);

        } else {
            // Ya hay una llamada activa → responder 503 y NO crear nueva transacción
            System.out.println("[Proxy-TX] Recibido INVITE estando BUSY → enviar 503 Service Unavailable.");
            sendServiceUnavailable(invite, sourceIp, sourcePort);
        }
    }

    /**
     * Gestiona un ACK recibido desde el llamante.
     * <ul>
     *   <li>Solo se reenviará si el Call-ID coincide con la llamada activa.</li>
     *   <li>Se reenvía al callee a través de {@link ProxyUserLayer#onAckFromCaller}.
     *   <li>No se cambia el estado BUSY, porque la llamada sigue hasta el BYE.
     *
     * @param ack mensaje ACK recibido del llamante.
     */
    private void handleAck(ACKMessage ack) throws IOException {
        String callId = ack.getCallId();

        if (activeCallId != null && activeCallId.equals(callId)) {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID activo → reenviar al callee.");
            userLayer.onAckFromCaller(ack);

            // La transacción INVITE ya ha terminado: el proxy vuelve a estar libre
            state = IDLE;
            activeCallId = null;
            System.out.println("[Proxy-TX] ACK procesado → Proxy vuelve a IDLE");

        } else {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID desconocido/antiguo → se ignora.");
        }
    }


    // ========================================================================
    //   ENVÍO / REENVÍO DE MENSAJES
    // ========================================================================

    /**
     * Envía la respuesta al REGISTER (200 OK o 404 Not Found) al UA
     * que se ha intentado registrar.
     *
     * @param reg     REGISTER original.
     * @param contact dirección "IP:puerto" donde hay que enviar la respuesta.
     * @param ok      si es {@code true} se envía 200 OK; si es {@code false} 404.
     */
    public void sendRegisterResponse(RegisterMessage reg,
                                     String contact,
                                     boolean ok) throws IOException {

        SIPMessage response;

        if (ok) {
            // 200 OK al REGISTER
            OKMessage okMsg = new OKMessage();
            okMsg.setVias(reg.getVias());
            okMsg.setToName(reg.getToName());
            okMsg.setToUri(reg.getToUri());
            okMsg.setFromName(reg.getFromName());
            okMsg.setFromUri(reg.getFromUri());
            okMsg.setCallId(reg.getCallId());
            okMsg.setcSeqNumber(reg.getcSeqNumber());
            okMsg.setcSeqStr(reg.getcSeqStr());
            okMsg.setContact(contact);
            okMsg.setExpires(reg.getExpires());
            okMsg.setContentLength(0);
            response = okMsg;
        } else {
            // 404 Not Found al REGISTER
            NotFoundMessage nf = new NotFoundMessage();
            nf.setVias(reg.getVias());
            nf.setToName(reg.getToName());
            nf.setToUri(reg.getToUri());
            nf.setFromName(reg.getFromName());
            nf.setFromUri(reg.getFromUri());
            nf.setCallId(reg.getCallId());
            nf.setcSeqNumber(reg.getcSeqNumber());
            nf.setcSeqStr(reg.getcSeqStr());
            nf.setContact(contact);
            nf.setExpires(reg.getExpires());
            nf.setContentLength(0);
            response = nf;
        }

        String[] parts = contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        transportLayer.send(response, ip, port);
    }

    /**
     * Reenvía un INVITE al UA destino (callee).
     *
     * @param inviteMessage INVITE que se debe reenviar.
     * @param address       IP destino.
     * @param port          puerto destino.
     */
    public void forwardInvite(InviteMessage inviteMessage,
                              String address,
                              int port) throws IOException {
        transportLayer.send(inviteMessage, address, port);
    }

    /**
     * Reenvía un 486 Busy Here desde el callee al caller.
     */
    public void forwardBusyHere(BusyHereMessage busy,
                                String ip,
                                int port) throws IOException {
        transportLayer.send(busy, ip, port);
    }

    /**
     * Reenvía un 408 Request Timeout desde el callee al caller.
     */
    public void forwardRequestTimeout(RequestTimeoutMessage rt,
                                      String ip,
                                      int port) throws IOException {
        transportLayer.send(rt, ip, port);
    }

    /**
     * Envía un 404 Not Found al caller cuando el callee
     * no está registrado o no se encuentra.
     *
     * @param inviteMessage  INVITE original.
     * @param callerContact  dirección "IP:puerto" del llamante.
     */
    public void sendInviteNotFound(InviteMessage inviteMessage,
                                   String callerContact) throws IOException {

        NotFoundMessage nf = new NotFoundMessage();
        nf.setVias(inviteMessage.getVias());
        nf.setToName(inviteMessage.getToName());
        nf.setToUri(inviteMessage.getToUri());
        nf.setFromName(inviteMessage.getFromName());
        nf.setFromUri(inviteMessage.getFromUri());
        nf.setCallId(inviteMessage.getCallId());
        nf.setcSeqNumber(inviteMessage.getcSeqNumber());
        nf.setcSeqStr(inviteMessage.getcSeqStr());
        nf.setContentLength(0);

        String[] parts = callerContact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        transportLayer.send(nf, ip, port);
    }

    /**
     * Reenvía un 200 OK al INVITE desde el callee al caller.
     *
     * @param ok      mensaje 200 OK recibido del callee.
     * @param address IP del caller.
     * @param port    puerto del caller.
     */
    public void forwardInviteOk(OKMessage ok,
                                String address,
                                int port) throws IOException {
        transportLayer.send(ok, address, port);
    }

    /**
     * Envía un 100 Trying al llamante cuando el proxy ha
     * recibido el INVITE y está empezando a gestionarlo.
     *
     * @param invite INVITE original.
     * @param ip     IP del llamante.
     * @param port   puerto del llamante.
     */
    public void sendTrying(InviteMessage invite, String ip, int port) throws IOException {
        TryingMessage trying = new TryingMessage();

        // Copiamos cabeceras básicas del INVITE
        trying.setVias(invite.getVias());
        trying.setToName(invite.getToName());
        trying.setToUri(invite.getToUri());
        trying.setFromName(invite.getFromName());
        trying.setFromUri(invite.getFromUri());
        trying.setCallId(invite.getCallId());
        trying.setcSeqNumber(invite.getcSeqNumber());
        trying.setcSeqStr(invite.getcSeqStr());  // "INVITE"
        trying.setContentLength(0);

        transportLayer.send(trying, ip, port);
    }

    /**
     * Reenvía un 180 Ringing desde el callee al caller.
     */
    public void forwardRinging(RingingMessage ringing, String ip, int port) throws IOException {
        transportLayer.send(ringing, ip, port);
    }

    /**
     * Reenvía un ACK desde el caller al callee.
     */
    public void forwardAck(ACKMessage ack, String ip, int port) throws IOException {
        transportLayer.send(ack, ip, port);
    }
    
    public void forwardBye(ByeMessage bye, String address, int port) throws IOException {
        transportLayer.send(bye, address, port);
    }


    /**
     * Envía un 503 Service Unavailable al INVITE cuando el proxy
     * ya está ocupado en otra llamada (estado BUSY).
     *
     * @param invite INVITE original.
     * @param ip     IP del llamante.
     * @param port   puerto del llamante.
     */
    private void sendServiceUnavailable(InviteMessage invite, String ip, int port) throws IOException {
        ServiceUnavailableMessage su = new ServiceUnavailableMessage();

        su.setVias(invite.getVias());
        su.setToName(invite.getToName());
        su.setToUri(invite.getToUri());
        su.setFromName(invite.getFromName());
        su.setFromUri(invite.getFromUri());
        su.setCallId(invite.getCallId());
        su.setcSeqNumber(invite.getcSeqNumber());
        su.setcSeqStr(invite.getcSeqStr());
        su.setContentLength(0);

        transportLayer.send(su, ip, port);
    }

    // ========================================================================
    //   CONTROL DEL TRANSPORTE
    // ========================================================================

    /**
     * Arranca la capa de transporte para que el proxy empiece a
     * escuchar datagramas UDP y procesar mensajes SIP.
     */
    public void startListening() {
        transportLayer.startListening();
    }
}
