package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.TryingMessage;

public class ProxyTransactionLayerOriginal {

    // Estado muy simple para la TRANSACCIÓN INVITE
    private static final int IDLE = 0;
    private static final int BUSY = 1;

    private int state = IDLE;

    // Call-ID de la llamada en curso (si la hay)
    private String activeCallId = null;

    // Con loose routing: true mientras la llamada está establecida (entre 200 OK INVITE y 200 OK BYE)
    private boolean dialogActive = false;

    private final boolean looseRouting;

    private ProxyUserLayer userLayer;
    private ProxyTransportLayer transportLayer;
    
    public ProxyTransactionLayerOriginal(int listenPort,
                                 ProxyUserLayer userLayer,
                                 boolean looseRouting) throws SocketException {
        this.userLayer     = userLayer;
        this.looseRouting  = looseRouting;
        this.transportLayer = new ProxyTransportLayer(listenPort, this);
    }

    /**
     * Punto central de entrada de TODOS los mensajes que llegan al proxy.
     */
    public void onMessageReceived(SIPMessage sipMessage,
                                  String sourceIp,
                                  int sourcePort) throws IOException {

        // 1) REGISTER
        if (sipMessage instanceof RegisterMessage) {
            userLayer.onRegisterReceived((RegisterMessage) sipMessage);
            return;
        }

        // 2) INVITE
        if (sipMessage instanceof InviteMessage) {
            handleInvite((InviteMessage) sipMessage, sourceIp, sourcePort);
            return;
        }

        // 3) 180 Ringing
        if (sipMessage instanceof RingingMessage) {
            userLayer.onRingingFromCallee((RingingMessage) sipMessage);
            return;
        }

        // 4) 200 OK (INVITE / BYE)
        if (sipMessage instanceof OKMessage) {
            OKMessage ok = (OKMessage) sipMessage;
            String cseqMethod = ok.getcSeqStr();

            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                userLayer.onInviteOKFromCallee(ok);

                if (looseRouting &&
                        activeCallId != null &&
                        activeCallId.equals(ok.getCallId())) {
                    dialogActive = true;
                    System.out.println("[Proxy-TX] 200 OK al INVITE → diálogo activo (loose routing).");
                } else {
                    // SIN loose routing: no esperamos ACK (porque va extremo-a-extremo)
                    if (activeCallId != null && activeCallId.equals(ok.getCallId())) {
                        state = IDLE;
                        dialogActive = false;
                        activeCallId = null;
                        System.out.println("[Proxy-TX] 200 OK al INVITE (sin loose routing) → proxy vuelve a IDLE.");
                    }
                }
                return;
            }

            if ("BYE".equalsIgnoreCase(cseqMethod)) {
                handleByeOk(ok);
                return;
            }
        }

        // 5) 404 Not Found al INVITE
        if (sipMessage instanceof NotFoundMessage) {
            NotFoundMessage nf = (NotFoundMessage) sipMessage;
            String cseqMethod = nf.getcSeqStr();
            if ("INVITE".equalsIgnoreCase(cseqMethod)) {
                System.out.println("[Proxy-TX] 404 Not Found para INVITE → el UA llamante lo gestionará.");
                return;
            }
        }

        // 6) ACK
        if (sipMessage instanceof ACKMessage) {
            handleAck((ACKMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof BusyHereMessage) {
            BusyHereMessage bh = (BusyHereMessage) sipMessage;
            userLayer.onBusyHereFromCallee(bh);

            if (!looseRouting && activeCallId != null && activeCallId.equals(bh.getCallId())) {
                state = IDLE;
                dialogActive = false;
                activeCallId = null;
                System.out.println("[Proxy-TX] 486 (sin loose routing) → proxy vuelve a IDLE.");
            }
            return;
        }

        if (sipMessage instanceof RequestTimeoutMessage) {
            RequestTimeoutMessage rt = (RequestTimeoutMessage) sipMessage;
            userLayer.onRequestTimeoutFromCallee(rt);

            if (!looseRouting && activeCallId != null && activeCallId.equals(rt.getCallId())) {
                state = IDLE;
                dialogActive = false;
                activeCallId = null;
                System.out.println("[Proxy-TX] 408 (sin loose routing) → proxy vuelve a IDLE.");
            }
            return;
        }


        // 9) BYE (solo si hay loose routing)
        if (sipMessage instanceof ByeMessage) {
            handleBye((ByeMessage) sipMessage);
            return;
        }

        System.err.println("[Proxy-TX] Mensaje inesperado de tipo "
                + sipMessage.getClass().getSimpleName() + ", se ignora.");
    }

    // ================== LÓGICA DE INVITE ==================

    private void handleInvite(InviteMessage invite,
                              String sourceIp,
                              int sourcePort) throws IOException {

        String callId = invite.getCallId();

        if (!hasActiveCall()) {
            // Primera llamada o no hay llamada en curso
            state        = BUSY;
            activeCallId = callId;
            dialogActive = false;

            System.out.println("[Proxy-TX] Nuevo INVITE (Call-ID=" + callId +
                    ") → Proxy pasa a BUSY.");

            userLayer.onInviteReceived(invite, sourceIp, sourcePort);

        } else {
            // Ya hay llamada en curso
            if (activeCallId != null && activeCallId.equals(callId)) {
                // Retransmisión del mismo INVITE: la ignoramos
                System.out.println("[Proxy-TX] INVITE duplicado para Call-ID activo → ignorado.");
                return;
            }

            System.out.println("[Proxy-TX] Recibido INVITE mientras proxy está ocupado → responder 503.");
            sendServiceUnavailable(invite, sourceIp, sourcePort);
        }
    }

    /**
     * true si el proxy debe considerarse “ocupado” para nuevos INVITE.
     *  - sin loose routing: solo durante la transacción INVITE (state == BUSY)
     *  - con loose routing: durante la transacción INVITE o mientras el diálogo siga activo
     */
    private boolean hasActiveCall() {
        if (!looseRouting) {
            return state == BUSY;
        } else {
            return state == BUSY || dialogActive;
        }
    }

    private void handleAck(ACKMessage ack) throws IOException {
        String callId = ack.getCallId();

        if (activeCallId != null && activeCallId.equals(callId)) {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID activo → reenviar al callee.");
            userLayer.onAckFromCaller(ack);

            // La transacción INVITE termina aquí (éxito o error).
            state = IDLE;
            System.out.println("[Proxy-TX] ACK procesado → Proxy vuelve a IDLE (transacción INVITE terminada).");
        } else {
            System.out.println("[Proxy-TX] ACK recibido para Call-ID desconocido/antiguo → se ignora.");
        }
    }

    // ================== BYE (solo loose routing) ==================

    private void handleBye(ByeMessage bye) throws IOException {
        if (!looseRouting) {
            System.out.println("[Proxy-TX] BYE recibido pero loose routing desactivado → se ignora.");
            return;
        }

        String callId = bye.getCallId();
        if (activeCallId == null || !activeCallId.equals(callId)) {
            System.out.println("[Proxy-TX] BYE recibido para Call-ID desconocido → se ignora.");
            return;
        }

        userLayer.onByeReceived(bye);
    }

    private void handleByeOk(OKMessage ok) throws IOException {
        if (!looseRouting) {
            System.out.println("[Proxy-TX] 200 OK al BYE recibido pero loose routing desactivado → se ignora.");
            return;
        }

        String callId = ok.getCallId();
        if (activeCallId == null || !activeCallId.equals(callId)) {
            System.out.println("[Proxy-TX] 200 OK al BYE para Call-ID desconocido → se ignora.");
            return;
        }

        userLayer.onByeOkFromCallee(ok);

        // Fin de diálogo: liberamos totalmente el proxy
        dialogActive = false;
        activeCallId = null;
        state = IDLE;
        System.out.println("[Proxy-TX] 200 OK al BYE procesado → fin de llamada, proxy libre.");
    }

    // ================== ENVÍO / REENVÍO ==================

    public void sendRegisterResponse(RegisterMessage reg,
                                     String contact,
                                     boolean ok) throws IOException {

        SIPMessage response;

        if (ok) {
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
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        transportLayer.send(response, ip, port);
    }

    public void forwardInvite(InviteMessage inviteMessage,
                              String address,
                              int port) throws IOException {
        transportLayer.send(inviteMessage, address, port);
    }

    public void forwardBusyHere(BusyHereMessage busy,
                                String ip,
                                int port) throws IOException {
        transportLayer.send(busy, ip, port);
    }

    public void forwardRequestTimeout(RequestTimeoutMessage rt,
                                      String ip,
                                      int port) throws IOException {
        transportLayer.send(rt, ip, port);
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
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        transportLayer.send(nf, ip, port);
    }

    public void forwardInviteOk(OKMessage ok,
                                String address,
                                int port) throws IOException {
        transportLayer.send(ok, address, port);
    }

    public void sendTrying(InviteMessage invite, String ip, int port) throws IOException {
        TryingMessage trying = new TryingMessage();
        trying.setVias(invite.getVias());
        trying.setToName(invite.getToName());
        trying.setToUri(invite.getToUri());
        trying.setFromName(invite.getFromName());
        trying.setFromUri(invite.getFromUri());
        trying.setCallId(invite.getCallId());
        trying.setcSeqNumber(invite.getcSeqNumber());
        trying.setcSeqStr(invite.getcSeqStr());
        trying.setContentLength(0);
        transportLayer.send(trying, ip, port);
    }

    public void forwardRinging(RingingMessage ringing, String ip, int port) throws IOException {
        transportLayer.send(ringing, ip, port);
    }

    public void forwardAck(ACKMessage ack, String ip, int port) throws IOException {
        transportLayer.send(ack, ip, port);
    }

    public void forwardBye(ByeMessage bye, String ip, int port) throws IOException {
        transportLayer.send(bye, ip, port);
    }

    public void forwardByeOk(OKMessage ok, String ip, int port) throws IOException {
        transportLayer.send(ok, ip, port);
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
