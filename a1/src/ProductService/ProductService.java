package microservices;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ProductService class implements a RESTful microservice for managing products.
 * It provides endpoints for product creation, retrieval, updating, and deletion.
 */
public class ProductService {
    private static int PORT;
    private static String DB_URL;
    private static final String DB_USER = "productservice_user";
    private static final String DB_PASS = "password";
    private static ExecutorService threadPool; 


    /**
     * The main method initializes the ProductService.
     * It loads configurations, sets up the database, and starts an HTTP server.
     *
     * @param args Command-line arguments (expects a path to config.json).
     * @throws IOException if the server fails to start.
     */
    public static void main(String[] args) throws IOException {
        loadConfig(args[0]);
        setupDatabase();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/product", new ProductHandler());
        server.createContext("/shutdown", new ShutdownHandler(server));
        threadPool = Executors.newFixedThreadPool(100);
        server.setExecutor(threadPool);        
        server.start();
        System.out.println("ProductService started on port " + PORT);
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
            PORT = config.getJSONObject("ProductService").getInt("port");
            DB_URL = "jdbc:postgresql://localhost:5432/productservice_db"; 
        } catch (Exception e) {
            System.err.println("Failed to load config file.");
            System.exit(1);
        }
    }

     /**
     * Creates the products table in SQLite if it does not already exist.
     */
    private static void setupDatabase() {
        try (Connection conn = connectDB()) {
            String sql = "CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, name TEXT, description TEXT, price REAL, quantity INTEGER);";
            conn.createStatement().execute(sql);
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
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC Driver not found", e);
        }
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    /**
     * Handles shutdown requests to terminate the ProductService gracefully.
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
                System.out.println("ProductService shutting down...");
                exchange.sendResponseHeaders(200, -1);
                server.stop(0); // Graceful shutdown
                threadPool.shutdown();
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    /**
     * Handles incoming HTTP requests for the `/product` endpoint.
     */
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (method.equalsIgnoreCase("POST")) {
                handlePost(exchange);
            } else if (method.equalsIgnoreCase("GET")) {
                handleGet(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }

        /**
         * Handles a POST request to create, update, or delete a product.
         *
         * @param exchange The HTTP exchange containing the request.
         * @throws IOException if an error occurs while processing.
         */
        private void handlePost(HttpExchange exchange) throws IOException {
            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes()));
            if (!request.has("command") || !request.has("id")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            // make sure all fields are present for create and delete commands  
            if ("create".equals(request.getString("command")) ) {
                if (!request.has("name") || !request.has("description") || !request.has("price") || !request.has("quantity")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }

            // delete only needs name, price, and quantity if they are provided
            if ("delete".equals(request.getString("command")) ) {
                if (!request.has("name") || !request.has("price") || !request.has("quantity")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }

            String command = request.getString("command");
            int id = request.getInt("id");

            String name = null;
            if (request.has("name")) {
                if (!(request.get("name") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                name = request.getString("name");
            }

            String description = null;
            if (request.has("description")) {
                if (!(request.get("description") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                description = request.getString("description");
            }
            
            Double price = null;
            if (request.has("price")) {
                try {
                    price = request.getDouble("price"); // Will throw an exception if not a valid double
                } catch (Exception e) {
                    System.out.println("Invalid price format. Must be a double.");
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
            }

            // check whether the quantity is provided as the right type 
            Integer quantity = null;
            if (request.has("quantity")) {
                if (!(request.get("quantity") instanceof Integer)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                quantity = request.getInt("quantity");
            }

            

            
            try (Connection conn = connectDB()) {
                if ("create".equals(command)) {

                    if (name.isEmpty() || description.isEmpty()) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }

                    if (productExists(id)) {
                        exchange.sendResponseHeaders(409, -1);
                        return;
                    }

                    if (price <= 0 ) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    
                    if (quantity < 0) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }

                    String sql = "INSERT INTO products (id, name, description, price, quantity) VALUES (?, ?, ?, ?, ?);";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, id);
                    stmt.setString(2, name);
                    stmt.setString(3, description);
                    stmt.setDouble(4, price);
                    stmt.setInt(5, quantity);
                    stmt.executeUpdate();
                    
                } else if ("update".equals(command)) {
                    if (!productExists(id)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    
                    // if all are null then return success 
                    if (name == null && description == null && price == null && quantity == null) {
                        sendUpdateResponse(exchange, id);
                        return;
                    }
            
                    // Only update the feilds that are provided 
                    StringBuilder sql = new StringBuilder("UPDATE products SET ");
                    boolean first = true;
                    if (name != null) {
                        if (name.isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        sql.append("name = ?");
                        first = false;
                    }
                    if (description != null) {
                        if (description.isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!first) sql.append(", ");
                        sql.append("description = ?");
                        first = false;
                        
                    }
                    if (price != null) {
                        if (request.has("price") && price <= 0) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!first) sql.append(", ");
                        sql.append("price = ?");
                        first = false;
                    }
                    if (quantity != null) {
                        if (request.has("quantity") && quantity <= 0) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!first) sql.append(", ");
                        sql.append("quantity = ?");
                    }
                    sql.append(" WHERE id = ?;");
                    

                    // Prepare the statement
                    PreparedStatement stmt = conn.prepareStatement(sql.toString());

                    int index = 1;

                    if (request.has("name")) {
                        stmt.setString(index++, request.getString("name"));
                    }
                    if (request.has("description")) {
                        stmt.setString(index++, request.getString("description"));
                    }
                    if (request.has("price")) {
                        stmt.setDouble(index++, request.getDouble("price"));
                    }
                    if (request.has("quantity")) {
                        stmt.setInt(index++, request.getInt("quantity"));
                    }
                    stmt.setInt(index, id);

                    // Execute the update
                    int rowsUpdated = stmt.executeUpdate();
                    if (rowsUpdated > 0) {
                        sendUpdateResponse(exchange, id);
                        return;
                    } else {
                        exchange.sendResponseHeaders(404, -1); // No rows updated
                    }
                    
                } else if ("delete".equals(command)) {
                    if (!productExists(id)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                
                    // Only delete if all fields match the product found
                    // Get the product fields
                    String sql_find = "SELECT id, name, price, quantity FROM products WHERE id = ?;";
                    PreparedStatement stmt_find = conn.prepareStatement(sql_find);
                    stmt_find.setInt(1, id);
                    ResultSet rs = stmt_find.executeQuery();
                    if (rs.next()) {
                        if (!rs.getString("name").equals(name) || rs.getDouble("price") != price || rs.getInt("quantity") != quantity) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                    }
                
                    String sql = "DELETE FROM products WHERE id = ?;";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, id);
                
                    // Execute the delete
                    int rowsDeleted = stmt.executeUpdate();
                    if (rowsDeleted > 0) {
                        JSONObject response = new JSONObject();
                        response.put("id", rs.getInt("id"));  // Include the id in the response
                        response.put("name", rs.getString("name"));
                        response.put("price", rs.getDouble("price"));
                        response.put("quantity", rs.getInt("quantity"));
                
                        String jsonResponse = response.toString();
                        exchange.sendResponseHeaders(200, jsonResponse.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(jsonResponse.getBytes());
                        os.close();
                    } else {
                        exchange.sendResponseHeaders(404, -1); // No rows deleted
                    }
                }
                sendJsonProductResponse(exchange, id, request);
            } catch (SQLException e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
        }

        /**
         * Handles a GET request to retrieve a product by ID.
         *
         * @param exchange The HTTP exchange containing the request.
         * @throws IOException if an error occurs while processing.
         */
        private void handleGet(HttpExchange exchange) throws IOException {
            String[] uriParts = exchange.getRequestURI().toString().split("/");
            if (uriParts.length != 3) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            int id = 0;
            try {
                id = Integer.parseInt(uriParts[2]);
            } catch (NumberFormatException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            try (Connection conn = connectDB()) {
                String sql = "SELECT id, name, description, price, quantity FROM products WHERE id = ?;";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    JSONObject response = new JSONObject();
                    response.put("id", rs.getInt("id"));
                    response.put("name", rs.getString("name"));
                    response.put("description", rs.getString("description"));
                    response.put("price", rs.getDouble("price"));
                    response.put("quantity", rs.getInt("quantity"));
                    String jsonResponse = response.toString();
                    exchange.sendResponseHeaders(200, jsonResponse.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(jsonResponse.getBytes());
                    os.close();
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (SQLException e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
        }

        /**
         * Checks if a product with the given ID exists in the database.
         *
         * @param id The product ID to check.
         * @return {@code true} if the product exists, otherwise {@code false}.
         * @throws SQLException if an error occurs while querying the database.
         */
        private boolean productExists(int id) throws SQLException {
            try (Connection conn = connectDB()) {
                String sql = "SELECT COUNT(*) FROM products WHERE id = ?;";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int count = rs.getInt(1);
                    return count > 0;
                } else {
                    return false;
                }            }
        }

        /**
         * Sends a JSON response back to the client.
         *
         * @param exchange The HTTP exchange object.
         * @param id The product ID.
         * @param postReq The JSON object containing the request data.
         * @throws IOException If an error occurs while sending the response.
         */
        private void sendJsonProductResponse(HttpExchange exchange, int id,JSONObject postReq  ) throws IOException {

            JSONObject response = new JSONObject();
            response.put("id", postReq.getInt("id"));
            response.put("name", postReq.getString("name"));
            response.put("description", postReq.getString("description"));
            response.put("price", postReq.getDouble("price"));
            response.put("quantity", postReq.getInt("quantity"));
            String jsonResponse = response.toString();
            exchange.sendResponseHeaders(200, jsonResponse.length());
            OutputStream os = exchange.getResponseBody();
            os.write(jsonResponse.getBytes());
            os.close();
            
        }

        /**
         * Sends a JSON response back to the client after an update.
         *
         * @param exchange The HTTP exchange object.
         * @param id The product ID.
         * @throws IOException If an error occurs while sending the response.
         */
        private void sendUpdateResponse(HttpExchange exchange, int id ) throws IOException {
            try (Connection conn = connectDB()) {
                // Fetch the updated product and send it in the response
                String sqlSelect = "SELECT id, name, description, price, quantity FROM products WHERE id = ?;";
                PreparedStatement stmtSelect = conn.prepareStatement(sqlSelect);
                stmtSelect.setInt(1, id);
                ResultSet rs = stmtSelect.executeQuery();

                if (rs.next()) {
                    JSONObject response = new JSONObject();
                    response.put("id", rs.getInt("id"));
                    response.put("name", rs.getString("name"));
                    response.put("description", rs.getString("description"));
                    response.put("price", rs.getDouble("price"));
                    response.put("quantity", rs.getInt("quantity"));

                    String jsonResponse = response.toString();
                    exchange.sendResponseHeaders(200, jsonResponse.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(jsonResponse.getBytes());
                    os.close();
                } else {
                    exchange.sendResponseHeaders(500, -1); // Internal server error
                }
            } catch (SQLException e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
            
        }

        


    }
}


