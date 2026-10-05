package com.openclaw.kbbridge;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class RemoteClientTest {
    @Test void responseDeadlineIncludesABodyThatStallsAfterHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                exchange.sendResponseHeaders(200,2);
                exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                Thread.sleep(3000);
                exchange.getResponseBody().write('}');
            } catch(Exception ignored) { }
            finally {exchange.close();}
        });
        server.start();
        try {
            var client=new RemoteClient(mock(BridgeProperties.class));
            assertTimeout(Duration.ofMillis(2500),()->assertThrows(IllegalStateException.class,()->client.call("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic","GET","/",null,1)));
        } finally {server.stop(0);}
    }
}
