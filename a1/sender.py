import requests
import time
import random
from concurrent.futures import ThreadPoolExecutor

services = [
    {"name": "UserService", "base_url": "http://127.0.0.1:14001/user"},
    {"name": "ProductService", "base_url": "http://127.0.0.1:15000/product"},
    {"name": "OrderService", "base_url": "http://127.0.0.1:14000/order"}
]

global_success = {service["name"]: 0 for service in services}
global_fail = {service["name"]: 0 for service in services}

user_id = 1
product_id = 1
order_id = 1

def is_successful(status_code):
    # Success = only if the response is one of the expected application-level codes
    return status_code in [200, 400, 401, 404, 409]

def send_request(service):
    global user_id, product_id, order_id

    if random.random() < 0.5:
        # POST Request
        if service["name"] == "UserService":
            payload = {
                "command": "create",
                "id": user_id,
                "username": f"user{user_id}",
                "email": f"user{user_id}@test.com",
                "password": "password"
            }
            url = service["base_url"]
            user_id += 1
        elif service["name"] == "ProductService":
            payload = {
                "command": "create",
                "id": product_id,
                "name": f"product{product_id}",
                "description": "desc",
                "price": 10.0,
                "quantity": 50
            }
            url = service["base_url"]
            product_id += 1
        elif service["name"] == "OrderService":
            if product_id == 1 or user_id == 1:
                return True  # logically skip
            payload = {
                "command": "place order",
                "id": str(order_id),
                "product_id": random.randint(1, max(1, product_id - 1)),
                "user_id": random.randint(1, max(1, user_id - 1)),
                "quantity": 1
            }
            url = service["base_url"]
            order_id += 1
        else:
            return True  # unknown service

        try:
            r = requests.post(url, json=payload, timeout=1)
            return is_successful(r.status_code)
        except Exception:
            return False
    else:
        # GET Request
        if service["name"] == "UserService":
            url = f"{service['base_url']}/{random.randint(1, max(1, user_id - 1))}"
        elif service["name"] == "ProductService":
            url = f"{service['base_url']}/{random.randint(1, max(1, product_id - 1))}"
        elif service["name"] == "OrderService":
            url = f"{service['base_url']}/{random.randint(1, max(1, order_id - 1))}"
        else:
            return True

        try:
            r = requests.get(url, timeout=1)
            return is_successful(r.status_code)
        except Exception:
            return False

def run_test(duration=5, threads=50):
    print(f"Starting load test for {duration} seconds with {threads} threads...\n")
    end_time = time.time() + duration

    def task():
        while time.time() < end_time:
            service = random.choice(services)
            success = send_request(service)
            if success:
                global_success[service["name"]] += 1
            else:
                global_fail[service["name"]] += 1

    with ThreadPoolExecutor(max_workers=threads) as executor:
        for _ in range(threads):
            executor.submit(task)

    print("\n==== FINAL RESULTS ====")
    for service in services:
        total = global_success[service["name"]] + global_fail[service["name"]]
        print(f"{service['name']}: {global_success[service['name']]} successful out of {total} attempted")

if __name__ == "__main__":
    run_test(duration=5, threads=100)