package microservices;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * The Inter-Service Communication Service (ISCS) acts as a central router for handling requests 
 * between the OrderService, UserService, and ProductService. It processes orders, validates users 
 * and product availability, and updates product stock accordingly.
 *
 * <p>API Endpoint:
 * - POST /route: Processes order requests from OrderService, checks user validity, product stock, 
 * and updates product quantities if necessary.</p>
 *
 * <p>Workflow:
 * 1. Receives order details from OrderService.
 * 2. Validates user existence via UserService.
 * 3. Checks product stock via ProductService.
 * 4. Updates product stock if order is valid.
 * 5. Returns success or failure response.</p>
 */
public class ISCS {
    private static int PORT;
    private static String USER_SERVICE_URL;
    private static String PRODUCT_SERVICE_URL;

     /**
     * The main method initializes the ISCS service, loads configurations, and starts the HTTP server.
     *
     * @param args Command-line arguments (expects config file path).
     * @throws IOException If the server fails to start.
     */
    public static void main(String[] args) throws IOException {
        loadConfig(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/route", new RequestRouter());
        server.createContext("/shutdown", new ShutdownHandler(server));
        server.setExecutor(null);
        server.start();
        System.out.println("ISCS started on port " + PORT);
    }

     /**
     * Loads configuration details from the provided JSON file.
     * Extracts port and service URLs for inter-service communication.
     *
     * @param filePath The path to the config file.
     */
    private static void loadConfig(String filePath) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            JSONObject config = new JSONObject(content);
            PORT = config.getJSONObject("InterServiceCommunication").getInt("port");
            USER_SERVICE_URL = "http://" + config.getJSONObject("UserService").getString("ip") + ":" + config.getJSONObject("UserService").getInt("port") + "/user";
            PRODUCT_SERVICE_URL = "http://" + config.getJSONObject("ProductService").getString("ip") + ":" + config.getJSONObject("ProductService").getInt("port") + "/product";
        } catch (Exception e) {
            System.err.println("Failed to load config file.");
            System.exit(1);
        }
    }

     /**
     * Handles Shutdown requests to gracefully stop the ISCS service.
     */
    static class ShutdownHandler implements HttpHandler {
        private final HttpServer server;
        
        /**
         * Constructor to initialize the shutdown handler.
         *
         * @param server The HTTP server instance.
         */
        public ShutdownHandler(HttpServer server) {
            this.server = server;
        }
    
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                System.out.println("ISCS shutting down...");
                exchange.sendResponseHeaders(200, -1);
                server.stop(0); // Graceful shutdown
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    /**
     * Handles routing of requests between services.
     * Validates user existence, checks product availability, and updates stock when applicable.
     */
    static class RequestRouter implements HttpHandler {
         /**
         * Handles incoming HTTP requests and determines the appropriate action.
         *
         * @param exchange The HTTP exchange object containing request details.
         * @throws IOException If an error occurs while processing the request.
         */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!method.equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes()));
            if (!request.has("user_id") || !request.has("product_id") || !request.has("quantity")) {
                sendJsonResponse(exchange, 400, "Missing required fields");
                return;
            }

            int userId = request.getInt("user_id");
            int productId = request.getInt("product_id");
            int quantity = request.getInt("quantity");

            if (!forwardRequest(USER_SERVICE_URL + "/" + userId, "GET", null)) {
                sendJsonResponse(exchange, 404, "User not found");
                return;
            }

            JSONObject productResponse = fetchRemoteService(PRODUCT_SERVICE_URL + "/" + productId);
            if (!productResponse.has("quantity")) {
                sendJsonResponse(exchange, 404, "Product not found");
                return;
            }

            if (productResponse.getInt("quantity") < quantity) {
                sendJsonResponse(exchange, 409, "QuantityExceeded");
                return;
            }

            JSONObject updateProduct = new JSONObject();
            updateProduct.put("command", "update");
            updateProduct.put("id", productId);
            updateProduct.put("quantity", productResponse.getInt("quantity") - quantity);

            if (!forwardRequest(PRODUCT_SERVICE_URL, "POST", updateProduct)) {
                sendJsonResponse(exchange, 500, "Error updating product stock");
                return;
            }

            sendJsonResponse(exchange, 200, "Success");
        }

        /**
         * Forwards requests to the appropriate service (UserService or ProductService).
         *
         * @param url The service URL to forward the request to.
         * @param method The HTTP method (GET, POST).
         * @param payload The request payload for POST requests (null for GET).
         * @return {@code true} if the request was successful (HTTP 200), otherwise {@code false}.
         * @throws IOException If an error occurs during communication.
         */
        private boolean forwardRequest(String url, String method, JSONObject payload) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            if (payload != null) {
                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes());
                os.close();
            }

            return conn.getResponseCode() == 200;
        }

         /**
         * Fetches data from a remote service (e.g., checking product availability).
         *
         * @param url The endpoint URL to retrieve data from.
         * @return A {@link JSONObject} containing the response data, or an empty object if the request fails.
         * @throws IOException If an error occurs during communication.
         */
        private JSONObject fetchRemoteService(String url) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                Scanner scanner = new Scanner(conn.getInputStream());
                StringBuilder response = new StringBuilder();
                while (scanner.hasNext()) {
                    response.append(scanner.nextLine());
                }
                scanner.close();
                return new JSONObject(response.toString());
            }
            return new JSONObject();
        }

        /**
         * Sends a JSON response back to the client.
         *
         * @param exchange The HTTP exchange object.
         * @param statusCode The HTTP status code to return.
         * @param message The message to include in the response.
         * @throws IOException If an error occurs while sending the response.
         */
        private void sendJsonResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
            JSONObject response = new JSONObject().put("status", message);
            byte[] responseBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }
}

