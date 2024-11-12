console.log("Starting...");

const eventSource = new EventSource("http://localhost:8081/testing");
//const eventSource2 = new EventSource("http://localhost:8081/testing-2");

console.log("Created event source.");

eventSource.onmessage = (message) => {
    console.log("Received message", message);
};