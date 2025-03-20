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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The OrderService class implements a RESTful microservice for handling orders.
 * It processes order requests, interacts with the ISCS (Inter-Service Communication Service),
 * and manages order records in a SQLite database.
 */
public class OrderService {
    private static int PORT;
    private static String DB_URL;
    private static String ISCS_URL;
    private static String USER_SERVICE_URL;
    
     /**
     * The main method initializes the OrderService.
     * It loads configurations, sets up the database, and starts an HTTP server.
     *
     * @param args Command-line arguments (expects a path to config.json).
     * @throws IOException if the server fails to start.
     */
public static void main(String[] args) throws IOException {
    loadConfig(args[0]);

    // Initialize database based on restart status
    setupDatabase();

    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/order", new OrderHandler());
    server.createContext("/shutdown", new ShutdownHandler(server));
    server.createContext("/user/purchased", new OrderHandler());
    threadPool = Executors.newFixedThreadPool(100);
    server.setExecutor(threadPool);    server.start();
    System.out.println("OrderService started on port " + PORT);
}

    private static boolean doesUserExist(int userId) throws IOException {
        String url = USER_SERVICE_URL + "/" + userId; 
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int responseCode = conn.getResponseCode();
        conn.disconnect();
        return responseCode == 200;  // User exists if response is 200 OK
    }

    /**
     * Loads the configuration from a JSON file.
     *
     * @param filePath The path to the configuration file.
     */
    private static void loadConfig(String filePath) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            JSONObject config = new JSONObject(content);
            PORT = config.getJSONObject("OrderService").getInt("port");
            DB_URL = "jdbc:sqlite:compiled/OrderService/order_service.db";
            ISCS_URL = "http://" + config.getJSONObject("InterServiceCommunication").getString("ip") + ":" + config.getJSONObject("InterServiceCommunication").getInt("port") + "/route";
            USER_SERVICE_URL = "http://" + config.getJSONObject("UserService").getString("ip") + ":" + config.getJSONObject("UserService").getInt("port") + "/user";
        } catch (Exception e) {
            System.err.println("Failed to load config file.");
            System.exit(1);
        }
    }

    /**
     * Creates the orders table in SQLite if it does not already exist.
     */
private static void setupDatabase() {
    try (Connection conn = connectDB()) {
        String createOrdersTable = "CREATE TABLE IF NOT EXISTS orders (" +
                "id TEXT PRIMARY KEY, user_id INTEGER, product_id INTEGER, quantity INTEGER);";


        String createUserPurchasesTable = "CREATE TABLE IF NOT EXISTS user_purchases (" +
            "user_id INTEGER, " +
            "product_id INTEGER, " +
            "quantity INTEGER, " +
            "PRIMARY KEY (user_id, product_id));";

        conn.createStatement().execute(createOrdersTable);
        conn.createStatement().execute(createUserPurchasesTable);
    } catch (SQLException e) {
        e.printStackTrace();
    }
}


    /**
     * Establishes a connection to the SQLite database.
     *
     * @return A Connection object.
     * @throws SQLException if the connection fails.
     */
    private static Connection connectDB() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC Driver not found", e);
        }
        return DriverManager.getConnection(DB_URL);
    }

    /**
     * Handles shutdown requests to terminate the OrderService gracefully.
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
                System.out.println("Order Service shutting down...");
                exchange.sendResponseHeaders(200, -1);
                server.stop(0); // Graceful shutdown
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

      /**
     * Handles incoming HTTP requests for the `/order` endpoint.
     */
    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath(); 
            if (method.equalsIgnoreCase("POST")) {
                handlePost(exchange);
            } else if (method.equalsIgnoreCase("GET") && path.matches("/user/purchased/\\d+")) {
                handleGetPurchases(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }

    private void handleGetPurchases(HttpExchange exchange) throws IOException {
    String[] uriParts = exchange.getRequestURI().toString().split("/");
    int userId;

    try {
        userId = Integer.parseInt(uriParts[3]);
    } catch (NumberFormatException e) {
        exchange.sendResponseHeaders(400, -1);
        return;
    }

    try (Connection conn = connectDB()) {
        // Check if the user exists via UserService
    if (!doesUserExist(userId)) {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String jsonResponse = "{\"error\": \"User not found\"}";
        byte[] responseBytes = jsonResponse.getBytes();
        exchange.sendResponseHeaders(404, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
        return;
    }

        
        // Get user purchases
        String sql = "SELECT product_id, quantity FROM user_purchases WHERE user_id = ?;";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setInt(1, userId);
        ResultSet rs = stmt.executeQuery();

        JSONObject response = new JSONObject();
        boolean hasPurchases = false;

        while (rs.next()) {
            response.put(String.valueOf(rs.getInt("product_id")), rs.getInt("quantity"));
            hasPurchases = true;
        }

        OutputStream os = exchange.getResponseBody();
        if (!hasPurchases) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2);
            os.write("{}".getBytes());  // Empty JSON if user has no purchases
        } else {
            byte[] responseBytes = response.toString().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            os.write(responseBytes);
        }
        os.close();

    } catch (SQLException e) {
        exchange.sendResponseHeaders(500, -1);
        e.printStackTrace();
    }
}


        /**
         * Processes an order creation request.
         *
         * @param exchange The HTTP exchange containing the request.
         * @throws IOException if an error occurs while processing.
         */
        private void handlePost(HttpExchange exchange) throws IOException {
            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes()));

            if (!request.has("command") || !request.has("user_id") || !request.has("product_id") || !request.has("quantity")) {
                sendJsonResponse(exchange, 400, "Invalid Request");
                return;
            }
            
            String command = request.getString("command");

            if ("place order".equals(command)) {
                int userId = request.getInt("user_id");
                int productId = request.getInt("product_id");
                int quantity = request.getInt("quantity");
                
                if (quantity <= 0) {
                    sendJsonResponse(exchange, 400, "Invalid Request");
                    return;
                }
                
                String orderID = processOrder(userId, productId, quantity);

                // Wait for ISCS to return a response
                // if the quantity exceeds the amount avaible in the product service then we have to return a error 
                int responded = forwardToISCS(userId, productId, quantity);

                //print response
        

                if (responded == 409) {
                    sendJsonResponse(exchange, 409, "Exceeded quantity limit");
                    return;
                } else if (responded == 404) {
                    sendJsonResponse(exchange, 404, "Invalid Request");
                    return;
                } else if (responded == 400) {
                    sendJsonResponse(exchange, 400, "Invalid Request");
                    return;
                } else if (responded == 405) {
                    sendJsonResponse(exchange, 405, "Invalid Request");  
                    return;
                }
                else if (responded == 500) {
                    sendJsonResponse(exchange, 500, "Invalid Request");
                    return;
                }

                sendSuccessResponse(exchange, request, orderID);
            } else {
                sendJsonResponse(exchange, 400, "Invalid Request");
            }
        }

         /**
         * Saves the order in the database.
         *
         * @param userId    The ID of the user placing the order.
         * @param productId The ID of the product being ordered.
         * @param quantity  The quantity ordered.
         * @return The generated order ID.
         */
        private String processOrder(int userId, int productId, int quantity) {
            try (Connection conn = connectDB()) {
                String orderId = UUID.randomUUID().toString();

                // Insert order into orders table
                String insertOrderSql = "INSERT INTO orders (id, user_id, product_id, quantity) VALUES (?, ?, ?, ?);";
                PreparedStatement orderStmt = conn.prepareStatement(insertOrderSql);
                orderStmt.setString(1, orderId);
                orderStmt.setInt(2, userId);
                orderStmt.setInt(3, productId);
                orderStmt.setInt(4, quantity);
                orderStmt.executeUpdate();

                // Update user_purchases table
                String updatePurchaseSql = "INSERT INTO user_purchases (user_id, product_id, quantity) " +
                                        "VALUES (?, ?, ?) ON CONFLICT(user_id, product_id) " +
                                        "DO UPDATE SET quantity = quantity + ?;";
                PreparedStatement purchaseStmt = conn.prepareStatement(updatePurchaseSql);
                purchaseStmt.setInt(1, userId);
                purchaseStmt.setInt(2, productId);
                purchaseStmt.setInt(3, quantity);
                purchaseStmt.setInt(4, quantity);
                purchaseStmt.executeUpdate();

                return orderId;
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        }
        

        /**
         * Sends the order to ISCS for processing.
         *
         * @param userId    The user ID.
         * @param productId The product ID.
         * @param quantity  The quantity ordered.
         * @return The HTTP response code from ISCS.
         * @throws IOException if an error occurs.
         */
        private int forwardToISCS(int userId, int productId, int quantity) throws IOException {
            JSONObject payload = new JSONObject();
            payload.put("user_id", userId);
            payload.put("product_id", productId);
            payload.put("quantity", quantity);
        
            HttpURLConnection conn = (HttpURLConnection) new URL(ISCS_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
        
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes());
            }
        
            int responseCode = conn.getResponseCode();
            return responseCode;
        }

        /**
         * Sends a JSON response to the client.
         *
         * @param exchange   The HTTP exchange.
         * @param statusCode The HTTP status code.
         * @param message    The response message.
         * @throws IOException if an error occurs while sending the response.
         */
        private void sendJsonResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
            JSONObject response = new JSONObject().put("status", message);
            byte[] responseBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }

        /**
         * Sends a success response to the client.
         *
         * @param exchange The HTTP exchange.
         * @param request  The original request.
         * @param orderID  The generated order ID.
         * @throws IOException if an error occurs while sending the response.
         */
        private void sendSuccessResponse(HttpExchange exchange, JSONObject request, String orderID ) throws IOException {
            JSONObject response = new JSONObject();
            response.put("id", orderID);
            response.put("product_id", request.getInt("product_id"));
            response.put("user_id", request.getInt("user_id"));
            response.put("quantity", request.getInt("quantity"));
            response.put("status", "Success");

            byte[] responseBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(200, responseBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }
}



