package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import sipServlet.User;
import sipServlet.Users;
import sipServlet.UsersServletReader;
import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import java.io.InputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

public class ProxyUserLayer {
	private ProxyTransactionLayer transactionLayer;
    // Info de registro de un usuario
    private static class RegistrationInfo {
        String contact;      // "IP:puerto" tal como viene en el REGISTER
        long   expiresAtMs;  // instante (en ms) en el que caduca
    }

    // Tabla: "sip:usuario@dominio" -> RegistrationInfo
    private Map<String, RegistrationInfo> registrations = new HashMap<>();

	public ProxyUserLayer(int listenPort) throws SocketException {
		this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
	}

	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		System.out.println("Received INVITE from " + inviteMessage.getFromName());
		ArrayList<String> vias = inviteMessage.getVias();
		String origin = vias.get(0);
		String[] originParts = origin.split(":");
		String originAddress = originParts[0];
		int originPort = Integer.parseInt(originParts[1]);
		transactionLayer.echoInvite(inviteMessage, originAddress, originPort);
	}

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
