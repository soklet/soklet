console.log("Starting...");

const eventSource = new EventSource("http://localhost:8081");

console.log("Created event source.");

eventSource.onmessage = (message) => {
    console.log("Received message", message);
};