Test case for a potentional bug with Netty 4.0.6 regarding request body chunking.

Just run the JUnit test, it will start a Jetty server that echoes the request body into the response. The test fails because it gets an empty response body.

The same kind of code works fine with Netty 3 (inspired from AHC's tests)