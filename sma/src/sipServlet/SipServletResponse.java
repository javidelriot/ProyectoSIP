package sipServlet;

/**
 * Implementación de SipServletResponseInterface.
 *
 * Representa la intención de enviar una respuesta SIP con cierto código
 * al INVITE asociado al SipServletRequest. La lógica concreta de
 * construcción/envío del mensaje SIP se hará más tarde en el contenedor;
 * aquí solo se registra la decisión en el request.
 */
public class SipServletResponse implements SipServletResponseInterface {

    private final SipServletRequest request;
    private final int statusCode;
    private boolean sent = false;

    public SipServletResponse(SipServletRequest request, int statusCode) {
        this.request = request;
        this.statusCode = statusCode;
    }

    @Override
    public void send() {
        // Evitamos registrar la decisión varias veces si el servlet llama
        // send() más de una vez sobre el mismo objeto.
        if (sent) {
            return;
        }
        sent = true;

        // Delegamos en el request para que guarde la decisión
        request.markResponseDecision(statusCode);
    }

    // Este getter puede ser útil para depuración o tests
    public int getStatusCode() {
        return statusCode;
    }
}
