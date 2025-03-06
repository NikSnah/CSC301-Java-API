#!/bin/bash

BASE_DIR="$(dirname "$(realpath "$0")")"
LIBS_DIR="$BASE_DIR/libs"

# Ensure the libs directory exists
mkdir -p "$LIBS_DIR"

install_pip() {
    echo "Checking for pip3..."
    
    if ! command -v pip3 &> /dev/null; then
        echo "pip3 not found. Installing pip3..."
        
        # Use Python's built-in ensurepip module to install pip
        python3 -m ensurepip --default-pip

        # If ensurepip fails, attempt manual install
        if ! command -v pip3 &> /dev/null; then
            echo "ensurepip failed. Installing manually..."
            curl -sS https://bootstrap.pypa.io/get-pip.py | python3
        fi
    fi

    echo "pip3 installed successfully!"
}

install_dependencies() {
    echo "Downloading required JAR libraries..."
    
    # SQLite JDBC
    SQLITE_JDBC_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar"
    SQLITE_JDBC_FILE="$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar"
    
    # JSON Library
    JSON_JAR_URL="https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar"
    JSON_JAR_FILE="$LIBS_DIR/json-20210307.jar"

    # Download SQLite JDBC if it doesn't exist
    if [ ! -f "$SQLITE_JDBC_FILE" ]; then
        echo "Downloading SQLite JDBC..."
        curl -L -o "$SQLITE_JDBC_FILE" "$SQLITE_JDBC_URL"
    else
        echo "SQLite JDBC already exists, skipping download."
    fi

    # Download JSON library if it doesn't exist
    if [ ! -f "$JSON_JAR_FILE" ]; then
        echo "Downloading JSON library..."
        curl -L -o "$JSON_JAR_FILE" "$JSON_JAR_URL"
    else
        echo "JSON library already exists, skipping download."
    fi

    echo "Installing Python dependencies..."
    
    # Install requests globally (not recommended for shared systems)
    python3 -m pip install --target="$LIBS_DIR" requests

    echo "All dependencies installed successfully."
}


compile() {

    echo "Compiling Java and python libraries..."
    # First ensure pip3 is installed
    install_pip
    install_dependencies

    echo "Compiling Java services..."
    mkdir -p "$BASE_DIR/compiled/UserService"
    mkdir -p "$BASE_DIR/compiled/ProductService"
    mkdir -p "$BASE_DIR/compiled/OrderService"
    mkdir -p "$BASE_DIR/compiled/ISCS"
    
    javac -cp "$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/UserService" "$BASE_DIR/src/UserService/"*.java
    javac -cp "$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/ProductService" "$BASE_DIR/src/ProductService/"*.java
    javac -cp "$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/OrderService" "$BASE_DIR/src/OrderService/"*.java
    javac -cp "$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/ISCS" "$BASE_DIR/src/ISCS/"*.java
    
    echo "Compilation complete."
}

start_user_service() {
    echo "Initializing UserService database..."
    sqlite3 "$BASE_DIR/compiled/UserService/user_service.db" "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT, email TEXT, password TEXT);"
    echo "Starting UserService..."
    java -cp "$BASE_DIR/compiled/UserService:$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" microservices.UserService "$BASE_DIR/config.json" 
}

start_product_service() {
    echo "Initializing ProductService database..."
    sqlite3 "$BASE_DIR/compiled/ProductService/product_service.db" "CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, name TEXT, description TEXT, price REAL, quantity INTEGER);"
    echo "Starting ProductService..."
    java -cp "$BASE_DIR/compiled/ProductService:$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" microservices.ProductService "$BASE_DIR/config.json" 
}

start_order_service() {
    echo "Initializing OrderService database..."
    sqlite3 "$BASE_DIR/compiled/OrderService/order_service.db" "CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY, product_id INTEGER, user_id INTEGER, quantity INTEGER);"
    echo "Starting OrderService..."
    java -cp "$BASE_DIR/compiled/OrderService:$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" microservices.OrderService "$BASE_DIR/config.json" 
}

start_iscs() {
    echo "Starting ISCS..."
    java -cp "$BASE_DIR/compiled/ISCS:$LIBS_DIR/sqlite-jdbc-3.42.0.0.jar:$LIBS_DIR/json-20210307.jar" microservices.ISCS "$BASE_DIR/config.json" 
}

start_workload_parser() {
    echo "Starting Workload Parser..."
    python3 "$BASE_DIR/workload_parser.py" "$2" 
}

delete_data() {
    echo "Deleting all data from UserService database..."
    sqlite3 "$BASE_DIR/compiled/UserService/user_service.db" "DELETE FROM users;"
    
    echo "Deleting all data from ProductService database..."
    sqlite3 "$BASE_DIR/compiled/ProductService/product_service.db" "DELETE FROM products;"
    
    echo "Deleting all data from OrderService database..."
    sqlite3 "$BASE_DIR/compiled/OrderService/order_service.db" "DELETE FROM orders; DELETE FROM user_purchases;"
    
    echo "All data deleted."
}

stop_services() {
    echo "Stopping all services gracefully..."
    
    CONFIG_FILE="$BASE_DIR/config.json"

    # Extract service IPs and ports from config.json
    USER_IP=$(jq -r '.UserService.ip' $CONFIG_FILE)
    USER_PORT=$(jq -r '.UserService.port' $CONFIG_FILE)
    
    PRODUCT_IP=$(jq -r '.ProductService.ip' $CONFIG_FILE)
    PRODUCT_PORT=$(jq -r '.ProductService.port' $CONFIG_FILE)
    
    ORDER_IP=$(jq -r '.OrderService.ip' $CONFIG_FILE)
    ORDER_PORT=$(jq -r '.OrderService.port' $CONFIG_FILE)
    
    ISCS_IP=$(jq -r '.InterServiceCommunication.ip' $CONFIG_FILE)
    ISCS_PORT=$(jq -r '.InterServiceCommunication.port' $CONFIG_FILE)

    # Define service shutdown URLs
    SERVICES=(
        "http://$USER_IP:$USER_PORT/shutdown"
        "http://$PRODUCT_IP:$PRODUCT_PORT/shutdown"
        "http://$ORDER_IP:$ORDER_PORT/shutdown"
        "http://$ISCS_IP:$ISCS_PORT/shutdown"
    )

    # Loop through services and send shutdown requests
    for URL in "${SERVICES[@]}"; do
        echo "Sending shutdown request to $URL..."
        curl -X POST --connect-timeout 5 "$URL" || echo "Failed to reach $URL"
    done

    echo "All services shutdown requested."
}

case "$1" in
    -c) compile ;;
    -u) start_user_service ;;
    -p) start_product_service ;;
    -i) start_iscs ;;
    -o) start_order_service ;;
    -w) start_workload_parser "$@" ;;
    -d) delete_data ;;  # New command to delete data
    -s) stop_services ;; # Stops all running services
    *) echo "Usage: ./runme.sh -c|-u|-p|-i|-o|-w workloadfile|-d|-s" ;;
esac