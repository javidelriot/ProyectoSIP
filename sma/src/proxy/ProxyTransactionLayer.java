package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.ACKMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.TryingMessage;

public class ProxyTransactionLayer {

    // Estado muy simple: solo controlamos si el proxy está libre u ocupado en UNA llamada
    private static final int IDLE = 0;
    private static final int BUSY = 1;

    private int state = IDLE;
    private String activeCallId = null;   // Call-ID de la llamada en curso

    private ProxyUserLayer userLayer;
    private ProxyTransportLayer transportLayer;

    public ProxyTransactionLayer(int listenPort, ProxyUserLayer userLayer) throws SocketException {
        this.userLayer = userLayer;
        this.transportLayer = new ProxyTransportLayer(listenPort, this);
    }

    /**
     * Punto central de entrada de TODOS los mensajes que llegan al proxy.
     */
    public void onMessageReceived(SIPMessage sipMessage, String sourceIp, int sourcePort) throws IOException {

        // 1) REGISTER: pasa a la UserLayer (que mantiene la tabla de registros)
        if (sipMessage instanceof RegisterMessage) {
            RegisterMessage reg = (RegisterMessage) sipMessage;
            userLayer.onRegisterReceived(reg);
            return;
        }

        // 2) INVITE (nuevo intento de llamada)
        if (sipMessage instanceof InviteMessage) {
            InviteMessage invite = (InviteMessage) sipMessage;
            handleInvite(invite, sourceIp, sourcePort);
            return;
        }

        // 3) 180 Ringing (desde el callee hacia el proxy)
        if (sipMessage instanceof RingingMessage) {
            RingingMessage ringing = (RingingMessage) sipMessage;
            userLayer.onRingingFromCallee(ringing);
            return;
        }

        // 4) 200 OK al INVITE (desde el callee hacia el proxy)
        if (sipMessage instanceof OKMessage) {
            OKMessage ok = (OKMessage) sipMessage;
            String cseqMethod = ok.getcSeqStr();
            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                userLayer.onInviteOKFromCallee(ok);
                return;
            }
            // En esta fase ignoramos otros OK (BYE, etc.)
        }

        // 5) 404 Not Found al INVITE (desde el proxy hacia el caller ya se gestiona arriba)
        if (sipMessage instanceof NotFoundMessage) {
            NotFoundMessage nf = (NotFoundMessage) sipMessage;
            String cseqMethod = nf.getcSeqStr();
            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                // El UA llamante lo gestionará (ya tienes handleInviteError en el UA)
                // Aquí el proxy no necesita hacer nada especial.
                System.out.println("[Proxy-TX] 404 Not Found para INVITE → reenvío ya hecho anteriormente.");
                return;
            }
        }

        // 6) ACK (desde el caller hacia el proxy)
        if (sipMessage instanceof ACKMessage) {
            ACKMessage ack = (ACKMessage) sipMessage;
            handleAck(ack);
            return;
        }

        System.err.println("[Proxy-TX] Mensaje inesperado de tipo "
                + sipMessage.getClass().getSimpleName() + ", se ignora.");
    }

    // ================== LÓGICA DE INVITE ==================

    private void handleInvite(InviteMessage invite, String sourceIp, int sourcePort) throws IOException {

        String callId = invite.getCallId();

        if (state == IDLE) {
            // Primera llamada: el proxy pasa a estar BUSY con este Call-ID
            state = BUSY;
            activeCallId = callId;

            System.out.println("[Proxy-TX] Nuevo INVITE (Call-ID=" + callId + ") → Proxy pasa a BUSY.");

            // Delegamos la lógica "de negocio" en la UserLayer
            // (verificación de registros, envío de 100 Trying, reenvío de INVITE al callee, etc.)
            userLayer.onInviteReceived(invite, sourceIp, sourcePort);

        } else {
            // Ya estamos en una llamada → según el enunciado, contestamos 503 y NO creamos transacción nueva
            System.out.println("[Proxy-TX] Recibido INVITE mientras proxy está BUSY → responder 503.");

            sendServiceUnavailable(invite, sourceIp, sourcePort);
            // No cambiamos state ni activeCallId; la llamada en curso sigue.
        }
    }

    private void handleAck(ACKMessage ack) throws IOException {
        String callId = ack.getCallId();

        // Solo reenviamos ACK si corresponde a la llamada activa
        if (activeCallId != null && activeCallId.equals(callId)) {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID activo → reenviar al callee.");
            userLayer.onAckFromCaller(ack);

            // A nivel de transacción INVITE, aquí podríamos considerar la transacción completada.
            // PERO la llamada (diálogo) sigue hasta el BYE, por lo que mantenemos el estado BUSY.
            // Cuando implementes BYE, ahí sí pasarás a IDLE.
        } else {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID desconocido/antiguo → se ignora.");
        }
    }

    // ================== ENVÍO DE RESPUESTAS ==================

    /**
     * Respuesta REGISTER: 200 OK o 404 Not Found al UA.
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

        // contact viene como "IP:puerto"
        String[] parts = contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        transportLayer.send(response, ip, port);
    }

    public void forwardInvite(InviteMessage inviteMessage,
                              String address,
                              int port) throws IOException {
        transportLayer.send(inviteMessage, address, port);
    }

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

    public void forwardInviteOk(OKMessage ok,
                                String address,
                                int port) throws IOException {
        transportLayer.send(ok, address, port);
    }

    public void sendTrying(InviteMessage invite, String ip, int port) throws IOException {
    	TryingMessage trying = new TryingMessage();
    	// Vias: mismas que el INVITE
        trying.setVias(invite.getVias());

        // To / From
        trying.setToName(invite.getToName());
        trying.setToUri(invite.getToUri());
        trying.setFromName(invite.getFromName());
        trying.setFromUri(invite.getFromUri());

        // Call-ID y CSeq
        trying.setCallId(invite.getCallId());
        trying.setcSeqNumber(invite.getcSeqNumber());
        trying.setcSeqStr(invite.getcSeqStr());  // "INVITE"

        // 100 Trying no lleva cuerpo
        trying.setContentLength(0);
        transportLayer.send(trying, ip, port);
    }

    public void forwardRinging(RingingMessage ringing, String ip, int port) throws IOException {
        transportLayer.send(ringing, ip, port);
    }

    public void forwardAck(ACKMessage ack, String ip, int port) throws IOException {
        transportLayer.send(ack, ip, port);
    }

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

    public void startListening() {
        transportLayer.startListening();
    }
}
