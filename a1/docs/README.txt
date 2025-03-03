Microservices System - README

Overview

This application consists of four microservices that interact with each other:
	1.	UserService - Manages users (Create, Update, Delete, Retrieve).
	2.	ProductService - Manages products (Create, Update, Delete, Retrieve).
	3.	OrderService - Handles orders and forwards them to ISCS.
	4.	ISCS (Inter-Service Communication System) - Routes order requests to UserService and ProductService.

The application includes a runme.sh script to compile, start, stop, clean, and test these services.

Setup Instructions

1️. Install Dependencies

Ensure you have the following installed:
	•	Java (JDK 11+) → Required to compile and run services.
	•	SQLite3 → Required for local database storage.
	•	Python 3 → Required for running the workload parser.
	•	jq (JSON processor) → Required for extracting values from config.json.
	•	cURL → Used to send HTTP shutdown requests.

Configuration
	•	The services rely on config.json, which contains IPs and ports for each service.
	•	Ensure config.json is in the same directory as runme.sh.

How to Use runme.sh

Run the following commands in a terminal inside the project directory.

 1. Compile all services

./runme.sh -c

This will:
	•	Compile all Java microservices.
	•	Store compiled files in the compiled/ directory.

2. Start individual services

Each service must be started separately.

Start UserService

./runme.sh -u

Start ProductService

./runme.sh -p

Start OrderService

./runme.sh -o

Start ISCS

./runme.sh -i

Each service will output:

[Service Name] started on port [PORT]

3. Run the Workload Parser

The workload parser automates API requests.

./runme.sh -w workload_file.txt

	•	workload_file.txt must contain test commands formatted as:

USER create <id> <username> <email> <password>
USER get <id>
USER update <id> username:<username> email:<email> password:<password>
USER delete <id> <username> <email> <password>
PRODUCT create <id> <name> <description> <price> <quantity>
PRODUCT info <id>
PRODUCT update <id> name:<name> description:<description> price:<price> quantity:<quantity>
PRODUCT delete <id> <name> <price> <quantity>
ORDER place <product_id> <user_id> <quantity>

	•	It will send HTTP requests to the corresponding services.

4. Clear All Data

To delete all database records:

./runme.sh -d

This will:
	•	Delete all users, products, and orders.
	•	Keep database structure intact.

5. Stop All Services

To gracefully shut down all running services, run:

./runme.sh -s

	•	The script will extract service IP and Port from config.json.
	•	Sends HTTP POST shutdown requests to:

http://[service_ip]:[port]/shutdown


	•	Each service prints:

[Service Name] shutting down...

♻️ Restarting Services
	1.	Stop all running services (if needed):

./runme.sh -s


	2.	Restart all services:

./runme.sh -u & ./runme.sh -p & ./runme.sh -o & ./runme.sh -i &

The & ensures services start asynchronously.

API Endpoints

The services expose the following HTTP endpoints:

UserService (/user)

Method	Endpoint	Description
POST	/user	Create, update, delete a user
GET	/user/{id}	Retrieve a user by ID
POST	/shutdown	Shutdown the service

ProductService (/product)

Method	Endpoint	Description
POST	/product	Create, update, delete a product
GET	/product/{id}	Retrieve a product by ID
POST	/shutdown	Shutdown the service

OrderService (/order)

Method	Endpoint	Description
POST	/order	Place an order (forwards to ISCS)
POST	/shutdown	Shutdown the service

ISCS (/route)

Method	Endpoint	Description
POST	/route	Routes order requests to UserService and ProductService
POST	/shutdown	Shutdown the service

Summary of Commands

Command	Description
./runme.sh -c	Compile all services
./runme.sh -u	Start UserService
./runme.sh -p	Start ProductService
./runme.sh -o	Start OrderService
./runme.sh -i	Start ISCS
./runme.sh -w workload.txt	Run workload parser
./runme.sh -d	Delete all data (users, products, orders)
./runme.sh -s	Stop all running services

	•	Ensure ports/IPs in config.json match those in your environment.
