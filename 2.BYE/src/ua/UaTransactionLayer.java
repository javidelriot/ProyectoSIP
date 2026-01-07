package ua;

import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.ByeMessage;

public class UaTransactionLayer {

    // (De momento no usamos una FSM global, pero dejamos la constante por si acaso)
    private static final int IDLE = 0;
    private int state = IDLE;

    // Transacciones de INVITE:
    //  - clientTxs: cuando este UA es el que llama
    //  - serverTxs: cuando este UA es el llamado
    private Map<String, InviteClientTransaction> clientTxs = new HashMap<>();
    private Map<String, InviteServerTransaction> serverTxs = new HashMap<>();

    // Último INVITE enviado por este UA (para construir el ACK al 200 OK)
    private InviteMessage lastInviteSent;

    private UaUserLayer userLayer;
    private UaTransportLayer transportLayer;

    /**
     * Constructor. Crea la capa de transporte y guarda la referencia al user layer.
     */
    public UaTransactionLayer(int listenPort,
                              String proxyAddress,
                              int proxyPort,
                              UaUserLayer userLayer) throws SocketException {
        this.userLayer = userLayer;
        this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, this);
    }

    /**
     * Envía un REGISTER al proxy.
     */
    public void sendRegister(RegisterMessage registerMessage) throws IOException {
        transportLayer.sendToProxy(registerMessage);
    }

    /**
     * Punto de entrada de todos los mensajes SIP que llegan al UA.
     * Decide qué hacer según el tipo de mensaje (REGISTER, INVITE, 180, 200, ACK...).
     */
    public void onMessageReceived(SIPMessage sipMessage) throws IOException {

        // --------- Respuestas al REGISTER ---------
        if (sipMessage instanceof OKMessage
                && ((OKMessage) sipMessage).getcSeqStr().equalsIgnoreCase("REGISTER")) {
            userLayer.onRegisterOK();
            return;
        }

        if (sipMessage instanceof NotFoundMessage
                && ((NotFoundMessage) sipMessage).getcSeqStr().equalsIgnoreCase("REGISTER")) {
            userLayer.onRegisterNotFound();
            return;
        }

        // --------- Lado cliente (yo llamo) ---------

        if (sipMessage instanceof TryingMessage) {
            handleTrying((TryingMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof RingingMessage) {
            handleRinging((RingingMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof OKMessage
                && ((OKMessage) sipMessage).getcSeqStr().equalsIgnoreCase("INVITE")) {
            handleInviteOK((OKMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof NotFoundMessage
                && ((NotFoundMessage) sipMessage).getcSeqStr().equalsIgnoreCase("INVITE")) {
            handleInviteError((NotFoundMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof BusyHereMessage) {
            // 486 Busy Here al INVITE
            userLayer.onBusyHereFromCallee((BusyHereMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof RequestTimeoutMessage) {
            // 408 Request Timeout al INVITE
            userLayer.onRequestTimeoutFromCallee((RequestTimeoutMessage) sipMessage);
            return;
        }
        
     // ----------- 200 OK al BYE -----------
        if (sipMessage instanceof OKMessage &&
            ((OKMessage) sipMessage).getcSeqStr().equalsIgnoreCase("BYE")) {
            userLayer.onByeOK();
            return;
        }

        // --------- Lado servidor (me llaman) ---------

        if (sipMessage instanceof InviteMessage) {
            handleIncomingInvite((InviteMessage) sipMessage);
            return;
        }

        if (sipMessage instanceof ACKMessage) {
            handleAck((ACKMessage) sipMessage);
            return;
        }
          
        // BYE: el otro extremo cuelga
        if (sipMessage instanceof ByeMessage) {
            handleBye((ByeMessage) sipMessage);
            return;
        }



        System.out.println("[UA-TX] Mensaje inesperado: " );
    }

    /**
     * Maneja un 100 Trying recibido cuando somos el llamante.
     */
    private void handleTrying(TryingMessage trying) {
        String callId = trying.getCallId();
        InviteClientTransaction tx = clientTxs.get(callId);

        if (tx == null) return;

        System.out.println("[UA-TX] Recibido 100 Trying (CALLING)");
    }

    /**
     * Maneja un 180 Ringing recibido cuando somos el llamante.
     * Actualiza el estado de la transacción y avisa al user layer.
     */
    private void handleRinging(RingingMessage ringing) {
        String callId = ringing.getCallId();
        InviteClientTransaction tx = clientTxs.get(callId);

        if (tx == null) return;

        tx.state = InviteClientTransaction.State.PROCEEDING;

        System.out.println("[UA-TX] Recibido 180 Ringing (PROCEEDING)");
        userLayer.onRinging();
    }

    /**
     * Maneja un 200 OK al INVITE cuando somos el llamante.
     * Pone la transacción en COMPLETED, envía el ACK, y programa
     * un timer para pasar a TERMINATED.
     */
    private void handleInviteOK(OKMessage ok) throws IOException {
        String callId = ok.getCallId();
        InviteClientTransaction tx = clientTxs.get(callId);

        if (tx == null) {
            System.out.println("[UA-TX] 200 OK recibido pero no existe transacción.");
            return;
        }

        System.out.println("[UA-TX] 200 OK al INVITE → COMPLETED → enviando ACK");

        tx.state = InviteClientTransaction.State.COMPLETED;

        // Enviar ACK al 200 OK
        sendAck(ok);

        // Programar paso a TERMINATED
        tx.terminationTimer = new Timer();
        tx.terminationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                tx.state = InviteClientTransaction.State.TERMINATED;
                System.out.println("[UA-TX] CLIENTE INVITE → TERMINATED");
                clientTxs.remove(callId);
            }
        }, 1000);

        // Avisar al user layer para que establezca la llamada
        userLayer.onInviteOKFromCallee(ok);
    }

    /**
     * Construye y envía el ACK para un 200 OK al INVITE.
     * Usa los datos del OK y del último INVITE enviado.
     */
    private void sendAck(OKMessage ok) throws IOException {

        ACKMessage ack = new ACKMessage();

        // Request-URI igual que el INVITE original
        ack.setDestination(lastInviteSent.getDestination());

        // Vías igual que el INVITE
        ack.setVias(lastInviteSent.getVias());

        // Max-Forwards igual
        ack.setMaxForwards(lastInviteSent.getMaxForwards());

        // To / From (usamos los del 200 OK para copiar el tag correcto)
        ack.setToName(ok.getToName());
        ack.setToUri(ok.getToUri());
        ack.setFromName(ok.getFromName());
        ack.setFromUri(ok.getFromUri());

        // Call-ID
        ack.setCallId(ok.getCallId());

        // CSeq con mismo número pero método ACK
        ack.setcSeqNumber(ok.getcSeqNumber());
        ack.setcSeqStr("ACK");

        // ACK no lleva cuerpo
        ack.setContentLength(0);

        transportLayer.sendToProxy(ack);
    }


    /**
     * Maneja un error 404 Not Found al INVITE cuando somos el llamante.
     * Pasa la transacción a TERMINATED y avisa al user layer.
     */
    private void handleInviteError(NotFoundMessage nf) {
        String callId = nf.getCallId();
        InviteClientTransaction tx = clientTxs.get(callId);

        if (tx == null) return;

        tx.state = InviteClientTransaction.State.COMPLETED;

        System.out.println("[UA-TX] Error en INVITE (404 Not Found).");

        userLayer.onInviteError();

        tx.state = InviteClientTransaction.State.TERMINATED;
        clientTxs.remove(callId);
    }

    /**
     * Maneja un INVITE entrante cuando este UA es el llamado.
     * Crea la transacción servidor, envía un 180 Ringing y avisa al user layer.
     */
    
    private void handleIncomingInvite(InviteMessage inv) throws IOException {
        String callId = inv.getCallId();

        // 1) Si ya estoy en llamada, respondo 486 Busy Here y no creo transacción
        if (userLayer.isInCall()) {
            System.out.println("[UA-TX] Recibido INVITE mientras estoy en llamada → 486 Busy Here");
            sendBusyForInvite(inv);   // ya tienes este método en esta misma clase
            return;
        }

        // 2) Si es un INVITE duplicado del mismo Call-ID, lo ignoro
        if (serverTxs.containsKey(callId)) {
            System.out.println("[UA-TX] INVITE duplicado ignorado.");
            return;
        }

        // 3) Caso normal: creo transacción servidor y envío 180 Ringing
        InviteServerTransaction tx = new InviteServerTransaction(callId, inv);
        serverTxs.put(callId, tx);

        System.out.println("[UA-TX] Recibido INVITE → PROCEEDING");

        RingingMessage ringing = new RingingMessage();

        ringing.setVias(inv.getVias());
        ringing.setRecordRoute(inv.getRecordRoute());

        ringing.setToName(inv.getToName());
        ringing.setToUri(inv.getToUri());
        ringing.setFromName(inv.getFromName());
        ringing.setFromUri(inv.getFromUri());

        ringing.setCallId(inv.getCallId());
        ringing.setcSeqNumber(inv.getcSeqNumber());
        ringing.setcSeqStr(inv.getcSeqStr());   // "INVITE"

        ringing.setContact(inv.getContact());
        ringing.setContentLength(0);

        transportLayer.sendToProxy(ringing);

        userLayer.onInviteReceived(inv);
    }


    /**
     * Maneja un ACK recibido cuando este UA es el llamado.
     * Marca la transacción servidor como TERMINATED y notifica al user layer.
     */
    private void handleAck(ACKMessage ack) {
        String callId = ack.getCallId();
        InviteServerTransaction tx = serverTxs.get(callId);

        if (tx == null) return;

        System.out.println("[UA-TX] ACK recibido → TERMINATED");

        tx.state = InviteServerTransaction.State.TERMINATED;
        serverTxs.remove(callId);

        userLayer.onAckReceived();
    }

    /**
     * Arranca la escucha de la capa de transporte.
     */
    public void startListeningNetwork() {
        transportLayer.startListening();
    }

    /**
     * Inicia una llamada saliente:
     *  - guarda el último INVITE enviado,
     *  - crea la transacción cliente,
     *  - envía el INVITE al proxy.
     */
    public void call(InviteMessage inviteMessage) throws IOException {

        this.lastInviteSent = inviteMessage;

        String callId = inviteMessage.getCallId();
        InviteClientTransaction tx = new InviteClientTransaction(callId, inviteMessage);
        clientTxs.put(callId, tx);

        System.out.println("[UA-TX] Enviando INVITE y creando transacción CLIENT (CALLING)");

        transportLayer.sendToProxy(inviteMessage);
    }

    /**
     * Envía un 486 Busy Here como respuesta al INVITE recibido
     * (por ejemplo si el usuario ha rechazado la llamada).
     */
    public void sendBusyForInvite(InviteMessage invite) throws IOException {
        BusyHereMessage busy = new BusyHereMessage();

        busy.setVias(invite.getVias());
        busy.setToName(invite.getToName());
        busy.setToUri(invite.getToUri());
        busy.setFromName(invite.getFromName());
        busy.setFromUri(invite.getFromUri());
        busy.setCallId(invite.getCallId());
        busy.setcSeqNumber(invite.getcSeqNumber());
        busy.setcSeqStr(invite.getcSeqStr()); // "INVITE"
        busy.setContentLength(0);

        transportLayer.sendToProxy(busy);
    }

    /**
     * Envía un 408 Request Timeout como respuesta al INVITE recibido
     * (si el usuario no acepta ni rechaza en el tiempo máximo).
     */
    public void sendRequestTimeoutForInvite(InviteMessage invite) throws IOException {
        RequestTimeoutMessage rt = new RequestTimeoutMessage();

        rt.setVias(invite.getVias());
        rt.setToName(invite.getToName());
        rt.setToUri(invite.getToUri());
        rt.setFromName(invite.getFromName());
        rt.setFromUri(invite.getFromUri());
        rt.setCallId(invite.getCallId());
        rt.setcSeqNumber(invite.getcSeqNumber());
        rt.setcSeqStr(invite.getcSeqStr()); // "INVITE"
        rt.setContentLength(0);

        transportLayer.sendToProxy(rt);
    }

    /**
     * Envía el 200 OK al INVITE cuando el usuario acepta la llamada.
     * Incluye el SDP y el Contact del UA.
     */
    public void sendOkForInvite(InviteMessage inviteMessage,
                                SDPMessage sdpMessage,
                                String contact) throws IOException {

        OKMessage ok = new OKMessage();

        ok.setVias(inviteMessage.getVias());

        ok.setToName(inviteMessage.getToName());
        ok.setToUri(inviteMessage.getToUri());
        ok.setFromName(inviteMessage.getFromName());
        ok.setFromUri(inviteMessage.getFromUri());

        ok.setCallId(inviteMessage.getCallId());
        ok.setcSeqNumber(inviteMessage.getcSeqNumber());
        ok.setcSeqStr(inviteMessage.getcSeqStr()); // "INVITE"

        ok.setContact(contact);

        ok.setContentLength(sdpMessage.toStringMessage().getBytes().length);
        ok.setSdp(sdpMessage);

        transportLayer.sendToProxy(ok);
    }
    
    private void handleBye(ByeMessage bye) throws IOException {
        System.out.println("[UA-TX] BYE recibido → enviando 200 OK directo al otro UA.");

        // Construimos el 200 OK al BYE
        OKMessage ok = new OKMessage();
        ok.setVias(bye.getVias());
        ok.setToName(bye.getToName());
        ok.setToUri(bye.getToUri());
        ok.setFromName(bye.getFromName());
        ok.setFromUri(bye.getFromUri());
        ok.setCallId(bye.getCallId());
        ok.setcSeqNumber(bye.getcSeqNumber());
        ok.setcSeqStr("BYE");
        ok.setContentLength(0);

        // Preguntamos al UaUserLayer a qué dirección mandar (IP:puerto del otro)
        String contact = userLayer.getCurrentRemoteContact();
        if (contact != null) {
            String[] parts = contact.split(":");
            String ip   = parts[0];
            int port    = Integer.parseInt(parts[1]);
            transportLayer.send(ok, ip, port);
        } else {
            System.out.println("[UA-TX] No hay remoteContact guardado, no puedo enviar 200 OK al BYE.");
        }

        // Avisamos a la capa de usuario para que pase a IDLE
        userLayer.onByeReceived();
    }


    public void sendByeDirect(ByeMessage bye, String ip, int port) throws IOException {
        transportLayer.send(bye, ip, port);
    }


}
