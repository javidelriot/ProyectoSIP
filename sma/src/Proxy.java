import proxy.ProxyUserLayer;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import sipServlet.Users;
import sipServlet.User;
import sipServlet.ServletClass;



public class Proxy {

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.out.println("Uso: java Proxy puertoEscucha looseRouting(true/false) debug(true/false)");
            return;
        }

        int listenPort      = Integer.parseInt(args[0]);
        boolean looseRouting = Boolean.parseBoolean(args[1]);
        boolean debug        = Boolean.parseBoolean(args[2]); // activa logs completos de SIP si es true

        System.out.println("Proxy launching with args: " +
                listenPort + ", " + looseRouting + ", " + debug);
        
        InputStream xml = Proxy.class.getResourceAsStream("/sipServlet/users.xml");
        if (xml == null) {
            System.out.println("[Proxy] WARNING: users.xml no encontrado. No se cargará ningún SIPServlet.");
        }

        Map<String, String> servletByUserUri = new HashMap<>();

        if (xml != null) {
            JAXBContext ctx = JAXBContext.newInstance(Users.class);
            Unmarshaller um = ctx.createUnmarshaller();
            Users users = (Users) um.unmarshal(xml);

            if (users.getListUsers() != null) {
                for (User u : users.getListUsers()) {
                    String userId    = u.getId();                         // ej: "sip:mario@it.uc3m.es"
                    ServletClass sc  = u.getServletClass();
                    if (sc != null && sc.getName() != null) {
                        String className = sc.getName();                 // ej: "example.sip.MarioSIPServlet"
                        servletByUserUri.put(userId, className);
                        System.out.println("[Proxy] Asociado " + userId + " -> " + className);
                    }
                }
            }
        }
        
        ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, looseRouting, debug, servletByUserUri);
userLayer.startListening();
    }
}
