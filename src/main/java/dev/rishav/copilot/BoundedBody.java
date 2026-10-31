package dev.rishav.copilot;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Cancels response consumption after the capture limit plus one lookahead byte. */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    BoundedBody(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for(var buffer:buffers) {
            int count=Math.min(buffer.remaining(),limit-bytes.size());byte[] chunk=new byte[count];buffer.get(chunk);bytes.writeBytes(chunk);
            if(bytes.size()>=limit) { subscription.cancel();result.complete(bytes.toByteArray());return; }
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
}
