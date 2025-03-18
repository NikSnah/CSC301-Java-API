import java.net.http.*;
import java.net.*;
import java.util.Random;

// https://openjdk.org/groups/net/httpclient/intro.html
// https://openjdk.org/groups/net/httpclient/recipes.html
// https://www.appsdeveloperblog.com/execute-an-http-put-request-in-java/

public class LoadTest {
	static char [] ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
	static Random rand;
  static int maxRequestsPerSecond = 100;
  // Change this to create random user/product/etc ids and then build up the JSON object. 
	public static String randomString(int length){
		StringBuilder r = new StringBuilder();
		for(int i=0;i<length;i++){
			int index = rand.nextInt(ALPHANUMERIC.length);
			r.append(ALPHANUMERIC[index]);
		}
		return r.toString();
	}
	public static void main(String [] args){
		if(args.length!=5){
			System.out.println("usage: java LoadTest HOST PORT SEED [PUT|GET] NUM_REQUESTS");
			System.exit(1);
		}
		String host = args[0];
		int port = Integer.parseInt(args[1]);
		int seed = Integer.parseInt(args[2]);
		String requestType = args[3];
		int numRequests = Integer.parseInt(args[4]);

		rand = new Random(seed);

		try {
      
     
			for(int i=0;i<numRequests;i++){
        // Modify to print helpful information if you'd like
        if (i % 100 == 0) {
          System.out.println(i);
        } 
        // You probably want to add timers to take into account the time that has passed already.
        Thread.sleep(1000/maxRequestsPerSecond);
				
        String longURL = "http://"+randomString(100);
				String shortURL = randomString(20);
				if(requestType.equals("PUT")){
					put("http://"+host+":"+port+"/?short="+shortURL+"&long="+longURL);
				} 
				if(requestType.equals("GET")){
					get("http://"+host+":"+port+"/"+shortURL);
				} 
				// get("http://mcs.utm.utoronto.ca");
				// get("http://localhost:8080/89M6VVVP7369R1VEPSP0");
			}
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	public static void put(String uri) throws Exception {
	    HttpClient client = HttpClient.newHttpClient();
	    HttpRequest request = HttpRequest.newBuilder()
	          .uri(URI.create(uri))
		  .PUT(HttpRequest.BodyPublishers.noBody())
	          .build();
	
	    HttpResponse<String> response =
	          client.send(request, HttpResponse.BodyHandlers.ofString());
	    // System.out.println(response.body());
	}
	public static void get(String uri) throws Exception {
	    HttpClient client = HttpClient.newHttpClient();
	    HttpRequest request = HttpRequest.newBuilder()
	          .uri(URI.create(uri))
		  .GET()
	          .build();
	
	    HttpResponse<String> response =
	          client.send(request, HttpResponse.BodyHandlers.ofString());
	
	    // System.out.println(response.body());
	}
}
