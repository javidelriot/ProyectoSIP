package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.NotFoundMessage;


public class ProxyTransactionLayer {
	private static final int IDLE = 0;
	private int state = IDLE;

	private ProxyUserLayer userLayer;
	private ProxyTransportLayer transportLayer;

	public ProxyTransactionLayer(int listenPort, ProxyUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new ProxyTransportLayer(listenPort, this);
	}

    public void onMessageReceived(SIPMessage sipMessage) throws IOException {

        // --- REGISTER ---
        if (sipMessage instanceof RegisterMessage) {
            RegisterMessage reg = (RegisterMessage) sipMessage;
            userLayer.onRegisterReceived(reg);
            return;
        }

        // --- INVITE (lo que ya tenías) ---
        if (sipMessage instanceof InviteMessage) {
            InviteMessage inviteMessage = (InviteMessage) sipMessage;
            switch (state) {
            case IDLE:
                userLayer.onInviteReceived(inviteMessage);
                break;
            default:
                System.err.println("Unexpected message, throwing away");
                break;
            }
        } else {
            System.err.println("Unexpected message, throwing away");
        }
    }

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
		String ip   = parts[0];
		int    port = Integer.parseInt(parts[1]);
		
		transportLayer.send(response, ip, port);
		}

	public void echoInvite(InviteMessage inviteMessage, String address, int port) throws IOException {
		transportLayer.send(inviteMessage, address, port);
	}

	public void startListening() {
		transportLayer.startListening();
	}
}
