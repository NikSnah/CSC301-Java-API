#!/bin/bash

BASE_DIR="$(dirname "$(realpath "$0")")"
LIBS_DIR="$BASE_DIR/libs"

if [[ "$OSTYPE" == "darwin"* ]]; then
    PLATFORM="mac"
else
    PLATFORM="linux"
fi

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
    
    # PostgreSQL JDBC
    POSTGRES_JDBC_URL="https://jdbc.postgresql.org/download/postgresql-42.2.27.jar"
    POSTGRES_JDBC_FILE="$LIBS_DIR/postgresql-42.2.27.jar"
    
    # JSON Library
    JSON_JAR_URL="https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar"
    JSON_JAR_FILE="$LIBS_DIR/json-20210307.jar"

    # Download PostgreSQL JDBC if it doesn't exist
    if [ ! -f "$POSTGRES_JDBC_FILE" ]; then
        echo "Downloading PostgreSQL JDBC..."
        curl -L -o "$POSTGRES_JDBC_FILE" "$POSTGRES_JDBC_URL"
    else
        echo "PostgreSQL JDBC already exists, skipping download."
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
    
    javac -cp "$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/UserService" "$BASE_DIR/src/UserService/"*.java
    javac -cp "$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/ProductService" "$BASE_DIR/src/ProductService/"*.java
    javac -cp "$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/OrderService" "$BASE_DIR/src/OrderService/"*.java
    javac -cp "$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" -d "$BASE_DIR/compiled/ISCS" "$BASE_DIR/src/ISCS/"*.java
    
    echo "Compilation complete."
}

setup_postgres_db() {
    local dbname=$1
    local dbuser=$2
    local dbpass=$3

    if [[ "$PLATFORM" == "mac" ]]; then
        if ! brew services list | grep -q "postgresql@14.*started"; then
        brew services start postgresql@14 >/dev/null 2>&1
        fi
        PSQL_CMD="psql postgres"
    else
        sudo service postgresql start
        PSQL_CMD="sudo -u postgres psql"
    fi

    # Create user if needed
    $PSQL_CMD -q <<EOF
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_user WHERE usename = '$dbuser') THEN
        CREATE USER $dbuser WITH PASSWORD '$dbpass';
    END IF;
END
\$\$;
EOF

    # Create DB if needed
    $PSQL_CMD -q -tc "SELECT 1 FROM pg_database WHERE datname = '$dbname'" | grep -q 1 || \
      $PSQL_CMD -q -c "CREATE DATABASE $dbname WITH OWNER $dbuser;"

    #Grant DB-level and table-level permissions
    $PSQL_CMD -q -c "GRANT ALL PRIVILEGES ON DATABASE $dbname TO $dbuser;"
    $PSQL_CMD -q -d "$dbname" -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $dbuser;"
    $PSQL_CMD -q -d "$dbname" -c "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $dbuser;"
    $PSQL_CMD -q -d "$dbname" -c "ALTER DEFAULT PRIVILEGES FOR ROLE $dbuser IN SCHEMA public GRANT ALL ON TABLES TO $dbuser;"
    $PSQL_CMD -q -d "$dbname" -c "ALTER DEFAULT PRIVILEGES FOR ROLE $dbuser IN SCHEMA public GRANT ALL ON SEQUENCES TO $dbuser;"

}

start_user_service() {
    echo "Starting UserService..."
    # Call DB setup before launching service
    setup_postgres_db "userservice_db" "userservice_user" "password"
    # Launch UserService with PostgreSQL JDBC driver
    java -cp "$BASE_DIR/compiled/UserService:$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" microservices.UserService "$BASE_DIR/config.json"
}

start_product_service() {
    echo "Starting ProductService..."
    setup_postgres_db "productservice_db" "productservice_user" "password"
    java -cp "$BASE_DIR/compiled/ProductService:$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" microservices.ProductService "$BASE_DIR/config.json"
}

start_order_service() {
    echo "Starting OrderService..."
    setup_postgres_db "orderservice_db" "orderservice_user" "password"
    java -cp "$BASE_DIR/compiled/OrderService:$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" microservices.OrderService "$BASE_DIR/config.json"
}

start_iscs() {
    echo "Starting ISCS..."
    java -cp "$BASE_DIR/compiled/ISCS:$LIBS_DIR/postgresql-42.2.27.jar:$LIBS_DIR/json-20210307.jar" microservices.ISCS "$BASE_DIR/config.json" 
}

start_workload_parser() {
    echo "Starting Workload Parser..."
    python3 "$BASE_DIR/workload_parser.py" "$2" 
}

delete_postgres_data() {
    local dbname=$1
    shift  # Remaining args are table names

    if [[ "$PLATFORM" == "mac" ]]; then
        PSQL_CMD="psql -d $dbname"
    else
        PSQL_CMD="sudo -u postgres psql -d $dbname"
    fi

    for table in "$@"; do
        echo "Deleting data from table: $table in DB: $dbname"
        $PSQL_CMD -q -c "DELETE FROM $table;" \
          || echo "Warning: Could not delete data from $table"
    done
}

delete_data() {
    echo "Deleting all data from UserService database..."
    delete_postgres_data "userservice_db" "users"
    
    echo "Deleting all data from ProductService database..."
    delete_postgres_data "productservice_db" "products"
    
    echo "Deleting all data from OrderService database..."
    delete_postgres_data "orderservice_db" "orders" "user_purchases"
    
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

restart_services() {
    echo "Restarting all services..."

# Added check to ensure that services are not already running, and stop them if they are running
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

    # Check if services are already running
    SERVICES_RUNNING=false

    for PORT in "$USER_PORT" "$PRODUCT_PORT" "$ORDER_PORT" "$ISCS_PORT"; do
        if lsof -i :$PORT >/dev/null 2>&1; then
            echo "[INFO] Detected a service already running on port $PORT."
            SERVICES_RUNNING=true
        fi
    done

    # If any services are running, stop them first
    if $SERVICES_RUNNING; then
        echo "[INFO] Some services are already running. Stopping them first..."
        stop_services
        sleep 2  # Give time for cleanup
    fi

    # Start all services
    ./runme.sh -c
    ./runme.sh -u &  # Start UserService
    ./runme.sh -p &  # Start ProductService
    ./runme.sh -o &  # Start OrderService
    ./runme.sh -i &  # Start ISCS
    echo "Waiting for services to start..."
    sleep 3  # Adjust this based on how long your services take to start

    echo "All services restarted".    
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
    -r) restart_services ;; # Restart all services
    *) echo "Usage: ./runme.sh -c|-u|-p|-i|-o|-w workloadfile|-d|-s|-r" ;;
esac