package brave.propagation;

import brave.internal.Nullable;
import com.google.auto.value.AutoValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static brave.internal.HexCodec.writeHexLong;

/**
 * Contains trace identifiers and sampling data propagated in and out-of-process.
 *
 * <p>Particularly, this includes trace identifiers and sampled state.
 *
 * <p>The implementation was originally {@code com.github.kristofa.brave.SpanId}, which was a
 * port of {@code com.twitter.finagle.tracing.TraceId}. Unlike these mentioned, this type does not
 * expose a single binary representation. That's because propagation forms can now vary.
 */
@AutoValue
//@Immutable
public abstract class TraceContext extends SamplingFlags {

  /**
   * Used to send the trace context downstream. For example, as http headers.
   *
   * <p>For example, to put the context on an {@link java.net.HttpURLConnection}, you can do this:
   * <pre>{@code
   * // in your constructor
   * injector = tracing.propagation().injector(URLConnection::setRequestProperty);
   *
   * // later in your code, reuse the function you created above to add trace headers
   * HttpURLConnection connection = (HttpURLConnection) new URL("http://myserver").openConnection();
   * injector.inject(span.context(), connection);
   * }</pre>
   */
  public interface Injector<C> {
    /**
     * Usually calls a setter for each propagation field to send downstream.
     *
     * @param traceContext possibly unsampled.
     * @param carrier holds propagation fields. For example, an outgoing message or http request.
     */
    void inject(TraceContext traceContext, C carrier);
  }

  /**
   * Used to join an incoming trace. For example, by reading http headers.
   *
   * @see brave.Tracer#nextSpan(TraceContextOrSamplingFlags)
   */
  public interface Extractor<C> {

    /**
     * Returns either a trace context or sampling flags parsed from the carrier. If nothing was
     * parsable, sampling flags will be set to {@link SamplingFlags#EMPTY}.
     *
     * @param carrier holds propagation fields. For example, an incoming message or http request.
     */
    TraceContextOrSamplingFlags extract(C carrier);
  }

  public static Builder newBuilder() {
    return new AutoValue_TraceContext.Builder().traceIdHigh(0L).debug(false)
        .extra(Collections.emptyList());
  }

  /** When non-zero, the trace containing this span uses 128-bit trace identifiers. */
  public abstract long traceIdHigh();

  /** Unique 8-byte identifier for a trace, set on all spans within it. */
  public abstract long traceId();

  /** The parent's {@link #spanId} or null if this the root span in a trace. */
  @Nullable public abstract Long parentId();

  // override as auto-value can't currently read the super-class's nullable annotation.
  @Override @Nullable public abstract Boolean sampled();

  /**
   * Unique 8-byte identifier of this span within a trace.
   *
   * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@linkplain #spanId}).
   */
  public abstract long spanId();

  /** @deprecated it is unnecessary overhead to propagate this property */
  @Deprecated public final boolean shared() {
    return false; // shared is set internally on Tracer.join
  }

  /**
   * Returns a list of additional data propagated through this trace.
   *
   * <p>The contents are intentionally opaque, deferring to {@linkplain Propagation} to define. An
   * example implementation could be storing a class containing a correlation value, which is
   * extracted from incoming requests and injected as-is onto outgoing requests.
   *
   * <p>Implementations are responsible for scoping any data stored here. This can be performed when
   * {@link Propagation.Factory#decorate(TraceContext)} is called.
   */
  public abstract List<Object> extra();

  public abstract Builder toBuilder();

  /** Returns the hex representation of the span's trace ID */
  public String traceIdString() {
    if (traceIdHigh() != 0) {
      char[] result = new char[32];
      writeHexLong(result, 0, traceIdHigh());
      writeHexLong(result, 16, traceId());
      return new String(result);
    }
    char[] result = new char[16];
    writeHexLong(result, 0, traceId());
    return new String(result);
  }

  /** Returns {@code $traceId/$spanId} */
  @Override
  public String toString() {
    boolean traceHi = traceIdHigh() != 0;
    char[] result = new char[((traceHi ? 3 : 2) * 16) + 1]; // 2 ids and the delimiter
    int pos = 0;
    if (traceHi) {
      writeHexLong(result, pos, traceIdHigh());
      pos += 16;
    }
    writeHexLong(result, pos, traceId());
    pos += 16;
    result[pos++] = '/';
    writeHexLong(result, pos, spanId());
    return new String(result);
  }

  @AutoValue.Builder
  public static abstract class Builder {
    /** @see TraceContext#traceIdHigh() */
    public abstract Builder traceIdHigh(long traceIdHigh);

    /** @see TraceContext#traceId() */
    public abstract Builder traceId(long traceId);

    /** @see TraceContext#parentId */
    public abstract Builder parentId(@Nullable Long parentId);

    /** @see TraceContext#spanId */
    public abstract Builder spanId(long spanId);

    /** @see TraceContext#sampled */
    public abstract Builder sampled(@Nullable Boolean nullableSampled);

    /** @see TraceContext#debug() */
    public abstract Builder debug(boolean debug);

    /** @deprecated it is unnecessary overhead to propagate this property */
    @Deprecated public final Builder shared(boolean shared) {
      // this is not a propagated property, rather set internal to Tracer.join
      return this;
    }

    /** @see TraceContext#extra() */
    public abstract Builder extra(List<Object> extra);

    abstract List<Object> extra();

    abstract TraceContext autoBuild();

    public final TraceContext build() {
      // make sure the extra data is immutable and unmodifiable
      return extra(ensureImmutable(extra())).autoBuild();
    }

    @Nullable abstract Boolean sampled();

    abstract boolean debug();

    Builder() { // no external implementations
    }
  }

  /** Only includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} */
  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof TraceContext)) return false;
    TraceContext that = (TraceContext) o;
    return (this.traceIdHigh() == that.traceIdHigh())
        && (this.traceId() == that.traceId())
        && (this.spanId() == that.spanId());
  }

  /** Only includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} */
  @Override public int hashCode() {
    long traceIdHigh = traceIdHigh(), traceId = traceId(), spanId = spanId();
    int h = 1;
    h *= 1000003;
    h ^= (int) ((traceIdHigh >>> 32) ^ traceIdHigh);
    h *= 1000003;
    h ^= (int) ((traceId >>> 32) ^ traceId);
    h *= 1000003;
    h ^= (int) ((spanId >>> 32) ^ spanId);
    return h;
  }

  TraceContext() { // no external implementations
  }

  static List<Object> ensureImmutable(List<Object> extra) {
    if (extra == Collections.EMPTY_LIST) return extra;
    // Faster to make a copy than check the type to see if it is already a singleton list
    if (extra.size() == 1) return Collections.singletonList(extra.get(0));
    return Collections.unmodifiableList(new ArrayList<>(extra));
  }
}
