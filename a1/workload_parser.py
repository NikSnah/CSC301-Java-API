

import json
import sys
import os
import subprocess

sys.path.append(os.path.join(os.path.dirname(__file__), 'libs'))

import requests

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

def load_config(config_file):
    """Load service configuration from a JSON file."""
    with open(config_file, 'r') as f:
        return json.load(f)

# component 1 stuff 


def shutdown_services():
    """Trigger service shutdown via Bash script."""
    print("[INFO] Shutting down all services...")
    subprocess.run(["./runme.sh", "-s"])  
    print("[INFO] Services stopped.")

def restart_services():
    """Restart services and reset databases if necessary."""
    print("[INFO] Restarting services...")
    subprocess.run(["./runme.sh", "-r"])  # Recompile Java services

def reset_databases():
    """Call the Bash script to reset all databases."""
    try:
        print("[INFO] Calling Bash script to delete all database data...")
        subprocess.run(["./runme.sh", "-d"])  # Calls the delete_data function in Bash
        print("[INFO] All databases reset successfully.")
    except Exception as e:
        print(f"[ERROR] Failed to reset databases: {e}")


def parse_workload(workload_file, config):
    """Parse and process commands from the workload file."""

    with open(workload_file, 'r') as f:
        lines = f.readlines()

    if not lines:  # If the workload file is empty, just return
        print("[INFO] Workload file is empty. Nothing to process.")
        return
     
    first_command_processed = False #ensures that first command checks for restart

    for i, line in enumerate(lines):
        line = line.strip()
        if not line or line.startswith("#"):  # Skip empty lines and comments
            continue

        parts = line.split()
        command = parts[0].lower()

        if not first_command_processed:  # Process the actual first valid command
            first_command_processed = True

            if command == "restart":
                print("[INFO] Restart detected at startup. Retaining database.")
                restart_services()
            else:
                print("[WARNING] Restart not detected at startup. Resetting database.")
                reset_databases()
                process_command(parts, config)
        else:
            if command == "shutdown":
                print("[INFO] Received shutdown command. Stopping services...")
                shutdown_services()
                return  # Stop processing further commands
            elif command == "restart":
                print("[INFO] Received restart command. Restarting services and resetting databases...")
                reset_databases()
                restart_services()
            else:
                # Process other commands
                process_command(parts, config)

def process_command(parts, config):
    """Process user, product, and order service commands."""
    service = parts[0].upper()
    command = parts[1].lower()

    if service == "USER":
        url = f"http://{config['UserService']['ip']}:{config['UserService']['port']}/user"
        if command == "create":
            payload = {
                "command": "create",
                "id": int(parts[2]),
                "username": parts[3],
                "email": parts[4],
                "password": parts[5],
            }
        elif command == "update":
            payload = {
                "command": "update",
                "id": int(parts[2]),
            }
            for field in parts[3:]:
                key, value = field.split(":")
                payload[key] = value
        elif command == "delete":
            payload = {"command": "delete", "id": int(parts[2])}
        elif command == "get":
            url = f"{url}/{int(parts[2])}"
            send_request("GET", url)
            return
        else:
            print(f"Unknown USER command: {command}")
            return
        send_request("POST", url, payload)

    elif service == "PRODUCT":
        url = f"http://{config['ProductService']['ip']}:{config['ProductService']['port']}/product"
        if command == "create":
            if len(parts) < 7:
                print("Invalid command format: create <id> <name> <description> <price> <quantity>")
                return
            payload = {
                "command": "create",
                "id": int(parts[2]),
                "name": parts[3],
                "description": parts[4],
                "price": float(parts[5]),
                "quantity": int(parts[6]),
            }
        elif command == "update":
            payload = {"command": "update", "id": int(parts[2])}
            for field in parts[3:]:
                key, value = field.split(":")
                payload[key] = value
                if key == "price":
                    payload[key] = float(value)
                elif key == "quantity":
                    payload[key] = int(value)
        elif command == "delete":
            payload = {"command": "delete", "id": int(parts[2])}
        elif command == "info":
            url = f"{url}/{int(parts[2])}"
            send_request("GET", url)
            return
        else:
            print(f"Unknown PRODUCT command: {command}")
            return
        send_request("POST", url, payload)

    elif service == "ORDER":
        url = f"http://{config['OrderService']['ip']}:{config['OrderService']['port']}/order"
        if command == "place":
            if len(parts) < 5:
                print("Invalid command format: place <product_id> <user_id> <quantity>")
                return
            payload = {
                "command": "place order",
                "product_id": int(parts[2]),
                "user_id": int(parts[3]),
                "quantity": int(parts[4]),
            }
            send_request("POST", url, payload)
        else:
            print(f"Unknown ORDER command: {command}")
    else:
        print(f"Unknown service: {service}")

def send_request(method, url, payload=None):
    """Send an HTTP request and print the response."""
    print(f"\n Sending {method} request to {url}")
    try:
        if method == "POST":
            response = requests.post(url, json=payload)
        elif method == "GET":
            response = requests.get(url)
        else:
            print(f"Unknown method: {method}")
            return

        print(f"Payload: {payload}")
        print(f"Response Code: {response.status_code}")
        print(f"Response: {response.json() if response.content else 'No Content'}")
    except requests.exceptions.RequestException as e:
        print(f"Request failed: {e}")
    except Exception as e:
        print(f"Error handling response: {e}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python workload_parser.py <workload_file.txt> ")
        sys.exit(1)

    config_file = os.path.join(BASE_DIR, "config.json")
    workload_file = os.path.join(BASE_DIR, sys.argv[1])

    config = load_config(config_file)
    parse_workload(workload_file, config)