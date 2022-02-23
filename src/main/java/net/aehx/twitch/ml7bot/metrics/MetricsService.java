package net.aehx.twitch.ml7bot.metrics;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class MetricsService {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsService.class);

    private MeterRegistry metricRegistry;
    private Thread metricThread;
    private PrometheusMeterRegistry prometheusRegistry;

    /**
     * @param metricsPrefix Common prefix for all DBRest metrics exported
     * @param prometheusEndpointEnabled even if false, the API allows registering metrics which will
     *                                  just not be exported.
     * @param prometheusHost host on which the prometheus endpoint should listen on
     * @param prometheusPort port on which the prometheus endpoint should listen on
     */
    public MetricsService(String metricsPrefix,
                          boolean prometheusEndpointEnabled,
                          String prometheusHost,
                          int prometheusPort) {
        try {
            // This prevents issues with recreating metrics in unit tests
            CollectorRegistry.defaultRegistry.clear();

            prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            metricRegistry = prometheusRegistry;

            metricRegistry.config()
                    .meterFilter(new MeterFilter() {
                        // Add common prefix to all metrics
                        @Override
                        public Meter.Id map(Meter.Id id) {
                            return id.withName(metricsPrefix + "." + id.getName());
                        }
                    });

            if (prometheusEndpointEnabled) {
                InetSocketAddress address = new InetSocketAddress(prometheusHost, prometheusPort);
                HttpServer server = HttpServer.create(address, 0);
                server.createContext("/metrics", httpExchange -> {
                    String response = prometheusRegistry.scrape();
                    httpExchange.sendResponseHeaders(200, response.getBytes().length);
                    try (OutputStream os = httpExchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

                metricThread = new Thread(server::start);
                metricThread.start();

                LOG.info("Prometheus Metrics Endpoint listening on http://{}:{}/metrics",
                        prometheusHost, prometheusPort);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public MeterRegistry getRegistry() {
        return metricRegistry;
    }
}
