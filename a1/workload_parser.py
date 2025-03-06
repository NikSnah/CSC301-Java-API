

import json
import sys
import os

sys.path.append(os.path.join(os.path.dirname(__file__), 'libs'))

import requests

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

def load_config(config_file):
    """Load service configuration from a JSON file."""
    with open(config_file, 'r') as f:
        return json.load(f)

def parse_workload(workload_file, config):
    """Parse and process commands from the workload file."""
    with open(workload_file, 'r') as f:
        for line in f:
            line = line.strip()
            
            if not line or line.startswith("#"):  # Skip empty lines and comments
                print(f"\nProcessing command: {line}")
                continue

            # Split the command into parts
            parts = line.split()
            service = parts[0].upper()
            command = parts[1].lower()

            # Get service URLs from config
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
                    payload = {
                        "command": "delete",
                        "id": int(parts[2]),
                        "username": parts[3],
                        "email": parts[4],
                        "password": parts[5],
                    }
                elif command == "get":
                    url = f"{url}/{int(parts[2])}"
                    send_request("GET", url)
                    continue
                else:
                    print(f"Unknown USER command: {command}")
                    continue
                send_request("POST", url, payload)

            elif service == "PRODUCT":
                url = f"http://{config['ProductService']['ip']}:{config['ProductService']['port']}/product"
                if command == "create":
                    payload = {
                        "command": "create",
                        "id": int(parts[2]),
                        "name": parts[3],
                        "description": parts[4],
                        "price": float(parts[5]),
                        "quantity": int(parts[6]),
                    }
                elif command == "update":
                    payload = {
                        "command": "update",
                        "id": int(parts[2]),
                    }
                    for field in parts[3:]:
                        key, value = field.split(":")
                        payload[key] = value
                        if key == "price":
                            payload[key] = float(value)
                        elif key == "quantity":
                            payload[key] = int(value)
                            
                elif command == "delete":
                    payload = {
                        "command": "delete",
                        "id": int(parts[2]),
                        "name": parts[3],
                        "description": parts[4],
                        "price": float(parts[5]),
                        "quantity": int(parts[6]),
                    }
                elif command == "info":
                    url = f"{url}/{int(parts[2])}"
                    send_request("GET", url)
                    continue
                else:
                    print(f"Unknown PRODUCT command: {command}")
                    continue
                send_request("POST", url, payload)

            elif service == "ORDER":
                url = f"http://{config['OrderService']['ip']}:{config['OrderService']['port']}/order"
                if command == "place":
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

    workload_file = os.path.join(BASE_DIR, sys.argv[1])
    

    config_file = os.path.join(BASE_DIR, "config.json")



    config = load_config(config_file)
    
    parse_workload(workload_file, config)
