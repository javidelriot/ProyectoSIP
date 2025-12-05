package ua;

import java.io.IOException;
import java.net.SocketException;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.RequestTimeoutMessage;


public class UaTransactionLayer {
	private static final int IDLE = 0;
	private int state = IDLE;
	
	// Tablas de transacciones
	private Map<String, InviteClientTransaction> clientTxs = new HashMap<>(); //Transacción invite del cliente que llama
	private Map<String, InviteServerTransaction> serverTxs = new HashMap<>(); //Transacción invite del cliente llamado

	// Último INVITE enviado (para ACK)
	private InviteMessage lastInviteSent;
	private UaUserLayer userLayer;
	private UaTransportLayer transportLayer;

	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, UaUserLayer userLayer)
			throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, this);
	}
	
	public void sendRegister(RegisterMessage registerMessage) throws IOException {
	    transportLayer.sendToProxy(registerMessage);
	}

	/**
	 * @param sipMessage
	 * @throws IOException
	 */
	public void onMessageReceived(SIPMessage sipMessage) throws IOException {
		// ----------- RESPUESTAS AL REGISTER -----------
	    if (sipMessage instanceof OKMessage && ((OKMessage) sipMessage).getcSeqStr().equalsIgnoreCase("REGISTER")) {
	        userLayer.onRegisterOK();
	        return;
	    }

	    if (sipMessage instanceof NotFoundMessage && ((NotFoundMessage) sipMessage).getcSeqStr().equalsIgnoreCase("REGISTER")) {
	        userLayer.onRegisterNotFound();
	        return;
	    }

	    // ----------- MANEJO DE INVITE COMO CLIENTE (yo llamo) -----------

	    if (sipMessage instanceof TryingMessage) {
	        handleTrying((TryingMessage) sipMessage);
	        return;
	    }

	    if (sipMessage instanceof RingingMessage) {
	        handleRinging((RingingMessage) sipMessage);
	        return;
	    }

	    if (sipMessage instanceof OKMessage && ((OKMessage) sipMessage).getcSeqStr().equalsIgnoreCase("INVITE")) {
	        handleInviteOK((OKMessage) sipMessage);
	        return;
	    }

	    if (sipMessage instanceof NotFoundMessage && ((NotFoundMessage) sipMessage).getcSeqStr().equalsIgnoreCase("INVITE")) {
	        handleInviteError((NotFoundMessage) sipMessage);
	        return;
	    }
	    
	    if(sipMessage instanceof BusyHereMessage) { //Va a ver que aplicar logica extra cuando se implemente el usuario ocupado por estar en llamada
	    	userLayer.onBusyHereFromCallee((BusyHereMessage) sipMessage);
	    	return;
	    }
	    
	    if(sipMessage instanceof RequestTimeoutMessage) { 
	    	userLayer.onRequestTimeoutFromCallee((RequestTimeoutMessage) sipMessage);
	    	return;
	    }

	    // ----------- MANEJO DE INVITE COMO SERVIDOR (me llaman) -----------

	    if (sipMessage instanceof InviteMessage) {
	        handleIncomingInvite((InviteMessage) sipMessage);
	        return;
	    }

	    if (sipMessage instanceof ACKMessage) {
	        handleAck((ACKMessage) sipMessage);
	        return;
	    }

	    System.out.println("[UA-TX] Mensaje inesperado: ");
	}	
	
	private void handleTrying(TryingMessage trying) {
	    String callId = trying.getCallId();
	    InviteClientTransaction tx = clientTxs.get(callId);

	    if (tx == null) return;

	    System.out.println("[UA-TX] Recibido 100 Trying (CALLING)");
	}
	
	private void handleRinging(RingingMessage ringing) {
	    String callId = ringing.getCallId();
	    InviteClientTransaction tx = clientTxs.get(callId);

	    if (tx == null) return;

	    tx.state = InviteClientTransaction.State.PROCEEDING;

	    System.out.println("[UA-TX] Recibido 180 Ringing (PROCEEDING)");
	    userLayer.onRinging();
	}
	
	private void handleInviteOK(OKMessage ok) throws IOException {
	    String callId = ok.getCallId();
	    InviteClientTransaction tx = clientTxs.get(callId);

	    if (tx == null) {
	        System.out.println("[UA-TX] 200 OK recibido pero no existe transacción.");
	        return;
	    }

	    System.out.println("[UA-TX] 200 OK al INVITE → COMPLETED → enviando ACK");

	    tx.state = InviteClientTransaction.State.COMPLETED;

	    sendAck(ok);

	    // timer → TERMINATED
	    tx.terminationTimer = new Timer();
	    tx.terminationTimer.schedule(new TimerTask() {
	        @Override
	        public void run() {
	            tx.state = InviteClientTransaction.State.TERMINATED;
	            System.out.println("[UA-TX] CLIENTE INVITE → TERMINATED");
	            clientTxs.remove(callId);
	        }
	    }, 1000);

	    userLayer.onInviteOKFromCallee(ok);
	}

	private void sendAck(OKMessage ok) throws IOException {

	    ACKMessage ack = new ACKMessage();

	    // Request-URI igual que INVITE original
	    ack.setDestination(lastInviteSent.getDestination());

	    // Vias igual que INVITE
	    ack.setVias(lastInviteSent.getVias());

	    // Max-Forwards igual
	    ack.setMaxForwards(lastInviteSent.getMaxForwards());

	    // To / From (importante copiar Tag)
	    ack.setToName(ok.getToName());
	    ack.setToUri(ok.getToUri());
	    ack.setFromName(ok.getFromName());
	    ack.setFromUri(ok.getFromUri());

	    // Call-ID
	    ack.setCallId(ok.getCallId());

	    // CSeq con método ACK pero mismo número
	    ack.setcSeqNumber(ok.getcSeqNumber());
	    ack.setcSeqStr("ACK");

	    // Contact
	    //ack.setContact(lastInviteSent.getContact());

	    // ACK no lleva cuerpo
	    ack.setContentLength(0);

	    transportLayer.sendToProxy(ack);
	}

	
	


	
	private void handleInviteError(NotFoundMessage nf) {
	    String callId = nf.getCallId();
	    InviteClientTransaction tx = clientTxs.get(callId);

	    if (tx == null) return;

	    tx.state = InviteClientTransaction.State.COMPLETED;

	    System.out.println("[UA-TX] Error en INVITE: ");

	    // No se manda ACK en esta práctica simplificada para errores, pero se podría.
	    userLayer.onInviteError();

	    tx.state = InviteClientTransaction.State.TERMINATED;
	    clientTxs.remove(callId);
	}

	private void handleIncomingInvite(InviteMessage inv) throws IOException {
	    String callId = inv.getCallId();

	    if (serverTxs.containsKey(callId)) {
	        System.out.println("[UA-TX] INVITE duplicado ignorado.");
	        return;
	    }

	    InviteServerTransaction tx = new InviteServerTransaction(callId, inv);
	    serverTxs.put(callId, tx);

	    System.out.println("[UA-TX] Recibido INVITE → PROCEEDING");

	    // Enviar 180 Ringing automáticamente
	    RingingMessage ringing = new RingingMessage();

	 // copiar las Vias tal cual
	 ringing.setVias(inv.getVias());

	 // (opcional) Record-Route si lo usas en tu escenario
	 ringing.setRecordRoute(inv.getRecordRoute());

	 // To / From igual que el INVITE
	 ringing.setToName(inv.getToName());
	 ringing.setToUri(inv.getToUri());
	 ringing.setFromName(inv.getFromName());
	 ringing.setFromUri(inv.getFromUri());

	 // Call-ID y CSeq
	 ringing.setCallId(inv.getCallId());
	 ringing.setcSeqNumber(inv.getcSeqNumber());
	 ringing.setcSeqStr(inv.getcSeqStr());   // "INVITE"

	 // Contact: dónde puede responderte el proxy / caller
	 // Puedes reutilizar el Contact del INVITE si quieres:
	 ringing.setContact(inv.getContact());

	 // Ringing no lleva cuerpo
	 ringing.setContentLength(0);

	 transportLayer.sendToProxy(ringing);
	    transportLayer.sendToProxy(ringing);

	    userLayer.onInviteReceived(inv);
	}
	
	private void handleAck(ACKMessage ack) {
	    String callId = ack.getCallId();
	    InviteServerTransaction tx = serverTxs.get(callId);

	    if (tx == null) return;

	    System.out.println("[UA-TX] ACK recibido → TERMINATED");

	    tx.state = InviteServerTransaction.State.TERMINATED;
	    serverTxs.remove(callId);

	    userLayer.onAckReceived();
	}



	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	public void call(InviteMessage inviteMessage) throws IOException {
		
	    this.lastInviteSent = inviteMessage;

	    // Crear transacción cliente
	    String callId = inviteMessage.getCallId();
	    InviteClientTransaction tx = new InviteClientTransaction(callId, inviteMessage);
	    clientTxs.put(callId, tx);

	    System.out.println("[UA-TX] Enviando INVITE y creando transacción CLIENT (CALLING)");

	    transportLayer.sendToProxy(inviteMessage);
	}

	public void sendBusyForInvite(InviteMessage invite) throws IOException {
	    BusyHereMessage busy = new BusyHereMessage();

	    // Reutilizamos Vias y cabeceras del INVITE
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
	
	public void sendOkForInvite(InviteMessage inviteMessage, SDPMessage sdpMessage, String contact) throws IOException {

		OKMessage ok = new OKMessage();

		// Reutilizamos las mismas Vias del INVITE
		ok.setVias(inviteMessage.getVias());

		// To / From: mismo esquema que el INVITE
		ok.setToName(inviteMessage.getToName());
		ok.setToUri(inviteMessage.getToUri());
		ok.setFromName(inviteMessage.getFromName());
		ok.setFromUri(inviteMessage.getFromUri());

		ok.setCallId(inviteMessage.getCallId());
		ok.setcSeqNumber(inviteMessage.getcSeqNumber());
		ok.setcSeqStr(inviteMessage.getcSeqStr()); // "INVITE"

		ok.setContact(contact);

		//ok.setContentType("application/sdp");
		ok.setContentLength(sdpMessage.toStringMessage().getBytes().length);
		ok.setSdp(sdpMessage);

		transportLayer.sendToProxy(ok);
	}

	
	
	
}
