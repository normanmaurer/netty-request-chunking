package slandelle;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ChunkingTest {

    protected Server server;
    protected int port1;

    public static class EchoHandler extends AbstractHandler {

        public void handle(String pathInContext, Request request,
                HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                throws IOException, ServletException {

            // respond with the same body as in the request
            byte[] bytes = new byte[20];
            if (bytes.length > 0) {
                int read = 0;
                while (read > -1) {
                    read = httpRequest.getInputStream().read(bytes);
                    if (read > 0) {
                        httpResponse.getOutputStream().write(bytes, 0, read);
                    }
                }
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    protected String getTargetUrl() {
        return String.format("http://127.0.0.1:%d/foo/test", port1);
    }

    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    @Before
    public void setUp() throws Exception {
        server = new Server();

        port1 = findFreePort();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);

        server.addConnector(listener);

        server.setHandler(configureHandler());
        server.start();
    }
    
    public void tearDown() throws Exception {
        server.stop();
    }

    // ------------------------------------------------------------------

    @Test
    public void testChunkedStream() throws Exception {
        EventLoopGroup eventLoop = new NioEventLoopGroup();
        Bootstrap plainBootstrap = new Bootstrap().channel(
                NioSocketChannel.class).group(eventLoop);

        final CountDownLatch latch = new CountDownLatch(1);
        
        // just placeholders
        final AtomicInteger status = new AtomicInteger(-1);
        final AtomicInteger responseBodySize = new AtomicInteger(-1);
        
        try {
            plainBootstrap.handler(new ChannelInitializer<Channel>() {

                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();

                    pipeline.addLast("httpHandler", new HttpClientCodec());
                    pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                    pipeline.addLast("httpProcessor", new WriteStreamHandler(latch, status, responseBodySize));
                }
            });
            
            
            ChannelFuture f = plainBootstrap.connect(new InetSocketAddress("127.0.0.1", port1));
            
            f.addListener(new ChannelFutureListener() {

                public void operationComplete(ChannelFuture future) throws Exception {
                    
                    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, getTargetUrl());
                    request.headers().add(HttpHeaders.Names.ACCEPT, "*/*");
                    request.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    request.headers().add(HttpHeaders.Names.HOST, "*127.0.0.1:" + port1);
                    request.headers().add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                    
                    future.channel().write(request);
                    future.channel().write(new ChunkedStream(new ByteArrayInputStream("hello".getBytes())));
                    future.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                }
            });

        } finally {
            latch.await();
            eventLoop.shutdownGracefully();
            Assert.assertEquals(200, status.get());
            Assert.assertTrue("Response body size should be > 0", responseBodySize.get() > 0);
        }
    }

    public final class WriteStreamHandler extends ChannelInboundHandlerAdapter {
        
        private final CountDownLatch latch;
        private final AtomicInteger status;
        private final AtomicInteger responseBodySize;
        
        public WriteStreamHandler(CountDownLatch latch, AtomicInteger status, AtomicInteger responseBodySize) {
            this.latch = latch;
            this.status = status;
            this.responseBodySize = responseBodySize;
        }
        
        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object e) throws Exception {
            
            if (e instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e;
                status.set(response.getStatus().code());
                
            } else if (e instanceof HttpContent) {
                HttpContent chunk = (HttpContent) e;
                responseBodySize.set(chunk.content().readableBytes());
                if (e instanceof LastHttpContent) {
                    latch.countDown();
                }
            }
        }
    }
}
