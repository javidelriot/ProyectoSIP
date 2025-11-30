package ua;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.SIPMessage;

public class UaTransactionLayer {
	private static final int IDLE = 0;
	private int state = IDLE;

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

	public void onMessageReceived(SIPMessage sipMessage) throws IOException {

	    // 1) --- RESPUESTAS AL REGISTER ---

	    // 200 OK
	    if (sipMessage instanceof OKMessage) {
	        OKMessage ok = (OKMessage) sipMessage;

	        // Miramos el método del CSeq: REGISTER, INVITE, BYE...
	        String cseqMethod = ok.getcSeqStr();
	        if ("REGISTER".equalsIgnoreCase(cseqMethod)) {
	            // Es un 200 OK a nuestro REGISTER
	            userLayer.onRegisterOK();
	            return;  // ya está manejado, no seguimos
	        }

	        // Si no es REGISTER, más adelante lo tratarás como 200 OK a INVITE/BYE
	    }

	    // 404 Not Found
	    if (sipMessage instanceof NotFoundMessage) {
	        NotFoundMessage nf = (NotFoundMessage) sipMessage;

	        String cseqMethod = nf.getcSeqStr();
	        if ("REGISTER".equalsIgnoreCase(cseqMethod)) {
	            // Es un 404 al REGISTER (usuario no permitido)
	            userLayer.onRegisterNotFound();
	            return;
	        }

	        // Si no es REGISTER, será un 404 a una llamada (lo harás después)
	    }

	    // 2) --- MENSAJES INVITE (lo que ya tenías) ---

	    if (sipMessage instanceof InviteMessage) {
	        InviteMessage inviteMessage = (InviteMessage) sipMessage;

	        switch (state) {
	        case IDLE:
	            // Estamos fuera de llamada: una nueva INVITE entrante
	            userLayer.onInviteReceived(inviteMessage);
	            break;

	        default:
	            System.err.println("Unexpected INVITE message in state " + state + ", throwing away");
	            break;
	        }
	    } else {
	        // De momento, cualquier otra cosa que no sea REGISTER resp. o INVITE la ignoramos
	        System.err.println("Unexpected message type (" + sipMessage.getClass().getSimpleName() + "), throwing away");
	    }
	}


	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	public void call(InviteMessage inviteMessage) throws IOException {
		transportLayer.sendToProxy(inviteMessage);
	}
}
