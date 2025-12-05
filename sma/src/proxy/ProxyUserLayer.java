package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import sipServlet.User;
import sipServlet.Users;
import sipServlet.UsersServletReader;
import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import common.FindMyIPv4;
import mensajesSIP.RingingMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.RequestTimeoutMessage;
import java.io.InputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

public class ProxyUserLayer {
	private ProxyTransactionLayer transactionLayer;
	private String proxyIp;
	private int    proxyPort;
	//private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();

    // Info de registro de un usuario
    private static class RegistrationInfo {
        String contact;      // "IP:puerto" tal como viene en el REGISTER
        long   expiresAtMs;  // instante (en ms) en el que caduca
    }

    // Tabla: "sip:usuario@dominio" -> RegistrationInfo
    private Map<String, RegistrationInfo> registrations = new HashMap<>();

    public ProxyUserLayer(int listenPort) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
        //this.proxyIp   = FindMyIPv4.findMyIPv4Address().getHostAddress();
        this.proxyPort = listenPort;
    }

	
	public void onInviteReceived(InviteMessage inviteMessage, String sourceIp, int sourcePort) throws IOException {

	    String callerUri = inviteMessage.getFromUri();
	    String calleeUri = inviteMessage.getToUri();

	    RegistrationInfo callerReg = getValidRegistration(callerUri);
	    RegistrationInfo calleeReg = getValidRegistration(calleeUri);

	    if (callerReg == null) {
	        System.out.println("[Proxy] Caller NO registrado → ignorando INVITE.");
	        return;
	    }

	    if (calleeReg == null) {
	        System.out.println("[Proxy] Callee NO registrado → enviando 404");
	        transactionLayer.sendInviteNotFound(inviteMessage, callerReg.contact);
	        return;
	    }

	    // 1) Enviar 100 Trying al llamante (usamos la IP:puerto desde donde llegó)
	    System.out.println("[Proxy] Enviando 100 Trying al llamante");
	    transactionLayer.sendTrying(inviteMessage, sourceIp, sourcePort);

	    // 2) Obtener dirección real del callee a partir de la tabla REGISTER
	    String[] parts = calleeReg.contact.split(":");
	    String destIp = parts[0];
	    int destPort = Integer.parseInt(parts[1]);

	    // 3) Añadir Via del proxy arriba
	    inviteMessage.getVias().add(0, destIp + ":" + proxyPort);

	    // 4) Reenviar el INVITE al UA llamado
	    System.out.println("[Proxy] Reenviando INVITE al callee " + calleeUri + " en " + destIp + ":" + destPort);
	    transactionLayer.forwardInvite(inviteMessage, destIp, destPort);
	}

	
	public void onRingingFromCallee(RingingMessage ringing) throws IOException {

	    String callerUri = ringing.getFromUri();
	    RegistrationInfo callerReg = getValidRegistration(callerUri);

	    if (callerReg == null) return;

	    String[] parts = callerReg.contact.split(":");
	    String ip = parts[0];
	    int port = Integer.parseInt(parts[1]);

	    System.out.println("[Proxy] Reenviando 180 Ringing al llamante");

	    transactionLayer.forwardRinging(ringing, ip, port);
	}
	
	public void onInviteOKFromCallee(OKMessage ok) throws IOException {

	    // El llamante es quien aparece en To del 200 OK
	    String callerUri = ok.getFromUri();
	    RegistrationInfo callerReg = getValidRegistration(callerUri);

	    if (callerReg == null) return;

	    String[] parts = callerReg.contact.split(":");
	    String ip = parts[0];
	    int port = Integer.parseInt(parts[1]);

	    System.out.println("[Proxy] Reenviando 200 OK al llamante");
	    transactionLayer.forwardInviteOk(ok, ip, port);
	}

	public void onAckFromCaller(ACKMessage ack) throws IOException {

	    String calleeUri = ack.getToUri();
	    RegistrationInfo calleeReg = getValidRegistration(calleeUri);

	    if (calleeReg == null) return;

	    String[] parts = calleeReg.contact.split(":");
	    String ip = parts[0];
	    int port = Integer.parseInt(parts[1]);

	    System.out.println("[Proxy] Reenviando ACK al callee");
	    transactionLayer.forwardAck(ack, ip, port);
	}

	
	
    public void onBusyHereFromCallee(BusyHereMessage busy) throws IOException {

        // En las respuestas, el From suele ser el caller original
        String callerUri = busy.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 486 Busy Here: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 486 Busy Here al llamante " 
                           + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardBusyHere(busy, ip, port);
    }
    
    public void onRequestTimeoutFromCallee(RequestTimeoutMessage rt) throws IOException {

        String callerUri = rt.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 408 Request Timeout: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 408 Request Timeout al llamante "
                           + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardRequestTimeout(rt, ip, port);
    }




//	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
//	    System.out.println("Proxy: recibido INVITE de " + inviteMessage.getFromUri() +
//	                       " para " + inviteMessage.getToUri());
//
//	    String callerUri = inviteMessage.getFromUri(); // ej: sip:alice@SMA
//	    String calleeUri = inviteMessage.getToUri();   // ej: sip:bob@SMA
//
//	    RegistrationInfo callerReg = getValidRegistration(callerUri);
//	    RegistrationInfo calleeReg = getValidRegistration(calleeUri);
//
//	    if (callerReg == null) {
//	        System.out.println("Proxy: caller no registrado, ignorando INVITE.");
//	        return;
//	    }
//
//	    if (calleeReg == null) {
//	        System.out.println("Proxy: callee no registrado o expirado → 404 al caller");
//	        transactionLayer.sendInviteNotFound(inviteMessage, callerReg.contact);
//	        return;
//	    }
//
//	    // Tenemos contact del callee, tipo "IP:puerto"
//	    String[] parts = calleeReg.contact.split(":");
//	    String destIp   = parts[0];
//	    int    destPort = Integer.parseInt(parts[1]);
//
//	    System.out.println("Proxy: reenviando INVITE a " + calleeUri +
//	                       " en " + destIp + ":" + destPort);
//
//	    transactionLayer.forwardInvite(inviteMessage, destIp, destPort);
//	}


	public void startListening() {
		transactionLayer.startListening();
	}
	
    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {

        // 1) Usuario SIP (ej: "sip:alice@SMA")
        String userUri = registerMessage.getToUri();

        // 2) Contact y Expires
        String contact = registerMessage.getContact();           // "IP:puerto"
        int expiresSec = Integer.parseInt(registerMessage.getExpires());

        System.out.println("REGISTER recibido de " + userUri +
                           " contact=" + contact +
                           " expires=" + expiresSec + "s");

        // 3) Comprobar si el usuario está permitido (de momento aceptamos todos)
        boolean valido = isUserAllowed(userUri);

        if (!valido) {
            // Usuario no permitido -> 404
            transactionLayer.sendRegisterResponse(registerMessage, contact, false);
            return;
        }

        // 4) Guardar/actualizar la tabla de registros
        RegistrationInfo info = new RegistrationInfo();
        info.contact     = contact;
        info.expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L;

        registrations.put(userUri, info);

        // 5) Responder 200 OK
        transactionLayer.sendRegisterResponse(registerMessage, contact, true);
    }
    
    // Devuelve la info de registro solo si el usuario está registrado y no ha expirado
    private RegistrationInfo getValidRegistration(String userUri) {
        RegistrationInfo info = registrations.get(userUri);
        if (info == null) {
            return null;
        }
        if (System.currentTimeMillis() > info.expiresAtMs) {
            return null;
        }
        return info;
    }

    private boolean isUserAllowed(String userUri) {
/*
        // userUri viene del REGISTER, tipo "sip:mario@it.uc3m.es"
        // En users.xml los ids son justo de ese estilo.

        try (InputStream xml = UsersServletReader.class
                .getResourceAsStream("users.xml")) {

            if (xml == null) {
                System.err.println("No se ha encontrado users.xml en el classpath");
                return false;
            }

            JAXBContext jaxbContext = JAXBContext.newInstance(Users.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();

            Users users = (Users) jaxbUnmarshaller.unmarshal(xml);

            // Recorremos la lista <user id="...">
            for (User u : users.getListUsers()) {
                String id = u.getId();   // atributo id="sip:loquesea"
                if (id != null && id.equalsIgnoreCase(userUri)) {
                    return true;        // usuario permitido
                }
            }

        } catch (Exception e) {
            System.err.println("Error leyendo users.xml: " + e.getMessage());
            e.printStackTrace();
        }

        return false; // si no está en el XML o hay error → no permitido
        */
    	return true;
    }





}
