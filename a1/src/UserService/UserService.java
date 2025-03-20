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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The UserService class provides an HTTP-based microservice for user management.
 * It supports operations to create, update, retrieve, and delete users, with password
 * hashing for security.
 */
public class UserService {
    private static int PORT;
    private static String DB_URL;

     /**
     * Initializes the UserService, loads configurations, sets up the database, 
     * and starts an HTTP server.
     *
     * @param args Command-line arguments (expects a path to config.json).
     * @throws IOException if the server fails to start.
     */
    public static void main(String[] args) throws IOException {
        loadConfig(args[0]);
        setupDatabase();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/user", new UserHandler());
        server.createContext("/shutdown", new ShutdownHandler(server));
        threadPool = Executors.newFixedThreadPool(100);
        server.setExecutor(threadPool);        server.start();
        System.out.println("UserService started on port " + PORT);
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
            PORT = config.getJSONObject("UserService").getInt("port");
            DB_URL = "jdbc:sqlite:compiled/UserService/user_service.db";
        } catch (Exception e) {
            System.err.println("Failed to load config file.");
            System.exit(1);
        }
    }

    /**
     * Creates the users table in SQLite if it does not already exist.
     */
    private static void setupDatabase() {
        try (Connection conn = connectDB()) {
            String sql = "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT UNIQUE, email TEXT UNIQUE, password TEXT);";
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
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC Driver not found", e);
        }
        return DriverManager.getConnection(DB_URL);
    }


    /**
     * Hashes a password using SHA-256 for security.
     *
     * @param password The plaintext password to hash.
     * @return The hashed password as a hexadecimal string.
     */
    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

     /**
     * Handles shutdown requests to terminate the UserService gracefully.
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
                System.out.println("UserService shutting down...");
                exchange.sendResponseHeaders(200, -1);
                server.stop(0); // Graceful shutdown
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

       /**
     * Handles incoming HTTP requests for the `/user` endpoint.
     */
    static class UserHandler implements HttpHandler {
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
         * Handles a POST request to create, update, or delete a user.
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

            String command = request.getString("command");
            int id = request.getInt("id");

            // Validate username type (if present)
            String username = null;
            if (request.has("username")) {
                if (!(request.get("username") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                username = request.getString("username");
            }

            // Validate email type (if present)
            String email = null;
            if (request.has("email")) {
                if (!(request.get("email") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                email = request.getString("email");
            }

            // Validate password type (if present)
            String password = null;
            if (request.has("password")) {
                if (!(request.get("password") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1); // Bad Request
                    return;
                }
                password = hashPassword(request.getString("password"));
            }
            
            try (Connection conn = connectDB()) {
                if ("create".equals(command)) {
                    if (userExists(id)) {
                        exchange.sendResponseHeaders(409, -1);
                        return;
                    }

                    // Check that the username, email, password exists and are not empty or null 
                    if (username == null || email == null || password == null || username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }

                    String sql = "INSERT INTO users (id, username, email, password) VALUES (?, ?, ?, ?);";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, id);
                    stmt.setString(2, username);
                    stmt.setString(3, email);
                    stmt.setString(4, password);
                    stmt.executeUpdate();
                } else if ("update".equals(command)) {
                    if (!userExists(id)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }

                    // when all three are null 
                    if (username == null && email == null && password == null) {
                        sendUpdateResponse(exchange, id);
                        return;
                    }

                    StringBuilder sql = new StringBuilder("UPDATE users SET ");
                    boolean first = true;
                    if (username != null) {
                        if (username.isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        sql.append("username = ?");
                        first = false;
                    }
                    if (email != null) {
                        if (email.isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!first) sql.append(", ");
                        sql.append("email = ?");
                        first = false;
                    }
                    if (password != null) {
                        if (password.isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!first) sql.append(", ");
                        sql.append("password = ?");
                    }
                    sql.append(" WHERE id = ?;");

                    PreparedStatement stmt = conn.prepareStatement(sql.toString());

                    int index = 1;
                    if (username != null) stmt.setString(index++, username);
                    if (email != null) stmt.setString(index++, email);
                    if (password != null) stmt.setString(index++, password);
                    stmt.setInt(index, id);
                    int rows = stmt.executeUpdate();

                    if (rows > 0 ) {
                        sendUpdateResponse(exchange, id);
                        return;
                    } 
                } else if ("delete".equals(command)) {
                    if (!userExists(id)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }

                    // Ensure all required fields are provided in the request
                    if (username == null || email == null || password == null) {
                        exchange.sendResponseHeaders(400, -1); // Bad Request - Missing required fields
                        return;
                    }
                    // make sure all the fields match to delete user 
                    // query and find user 
                    String sql_find = "SELECT * FROM users WHERE id = ?;";
                    PreparedStatement stmt_find = conn.prepareStatement(sql_find);
                    stmt_find.setInt(1, id);
                    ResultSet rs = stmt_find.executeQuery();

                    // Match the provided fields with the existing database record
                    if (!username.equals(rs.getString("username")) || 
                        !email.equals(rs.getString("email")) || 
                        !password.equals(rs.getString("password"))) 
                    {
                        exchange.sendResponseHeaders(400, -1); // Bad Request - Provided data does not match
                        return;
                    }

                    // delete the user
                
                    String sql = "DELETE FROM users WHERE id = ?;";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                sendJsonResponse(exchange, id, username, email, password );
            } catch (SQLException e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
        }

        /**
         * Handles a GET request to retrieve a user's information.
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

            sendJsonUserResponse(exchange, id);
        }

        /**
         * Establishes a connection to the SQLite database.
         *
         * @return A Connection object.
         * @throws SQLException if the connection fails.
         */
        private void sendJsonUserResponse(HttpExchange exchange, int id) throws IOException {
            try (Connection conn = connectDB()) {
                String sql = "SELECT * FROM users WHERE id = ?;";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    sendJsonResponse(exchange, rs.getInt("id"), rs.getString("username"), rs.getString("email"), rs.getString("password"));
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (SQLException e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
        }

        /**
         * Checks if a user exists in the database.
         *
         * @param id The user ID to check.
         * @return true if the user exists, false otherwise.
         * @throws SQLException if an error occurs while querying the database.
         */
        private boolean userExists(int id) throws SQLException {
            try (Connection conn = connectDB()) {
                String sql = "SELECT COUNT(*) FROM users WHERE id = ?;";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                ResultSet rs = stmt.executeQuery();
                return rs.getInt(1) > 0;
            }
        }

        /**
         * Sends a JSON response to the client.
         *
         * @param exchange The HTTP exchange object.
         * @param id The user ID.
         * @param username The username (optional).
         * @param email The email (optional).
         * @param password The password (optional).
         * @throws IOException if an error occurs while sending the response.
         */
        private void sendJsonResponse(HttpExchange exchange, int id, String username, String email, String password) throws IOException {
            JSONObject response = new JSONObject();
            response.put("id", id);
            if (username != null) response.put("username", username);
            if (email != null) response.put("email", email);
            if (password != null) response.put("password", password);
            String jsonResponse = response.toString();
            exchange.sendResponseHeaders(200, jsonResponse.length());
            OutputStream os = exchange.getResponseBody();
            os.write(jsonResponse.getBytes());
            os.close();
        }

        /**
         * Sends a JSON response back to the client after an update operation.
         *
         * @param exchange The HTTP exchange object.
         * @param statusCode The HTTP status code to return.
         * @param message The message to include in the response.
         * @throws IOException If an error occurs while sending the response.
         */
        private void sendUpdateResponse(HttpExchange exchange, int id) throws IOException {
            try (Connection conn = connectDB()) {
                String sql = "SELECT * FROM users WHERE id = ?;";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    JSONObject response = new JSONObject();
                    response.put("id", rs.getInt("id"));
                    response.put("username", rs.getString("username"));
                    response.put("email", rs.getString("email"));
                    response.put("password", rs.getString("password"));
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
    }
}

