package de.codecentric.boot.admin.server.cloud.extension;

import de.codecentric.boot.admin.server.cloud.discovery.DefaultServiceInstanceConverter;
import de.codecentric.boot.admin.server.cloud.discovery.ServiceInstanceConverter;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.HeartbeatMonitor;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.discovery.event.ParentHeartbeatEvent;
import org.springframework.cloud.consul.discovery.ConsulCatalogWatch;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.util.PatternMatchUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moss管理的实例发现监听
 *
 * @author xujin
 */
public class MossInstanceDiscoveryListener {
    private static final Logger log = LoggerFactory.getLogger(MossInstanceDiscoveryListener.class);
    private final InstanceRegistry registry;
    private final InstanceRepository repository;
    private final ThreadLocal<HeartbeatMonitor> threadLocalmonitor = new ThreadLocal<HeartbeatMonitor>();
    ThreadLocal<ConsulDiscoveryClient> consulDiscoveryClient = new ThreadLocal<ConsulDiscoveryClient>();
    private ServiceInstanceConverter converter = new DefaultServiceInstanceConverter();

    /**
     * Set of serviceIds to be ignored and not to be registered as application. Supports simple
     * patterns (e.g. "foo*", "*foo", "foo*bar").
     */
    private Set<String> ignoredServices = new HashSet<>();

    /**
     * Set of serviceIds that has to match to be registered as application. Supports simple
     * patterns (e.g. "foo*", "*foo", "foo*bar"). Default value is everything
     */
    private Set<String> services = new HashSet<>(Collections.singletonList("*"));
    private MultRegisterCenter multRegisterCenter;

    public MossInstanceDiscoveryListener(
            InstanceRegistry registry,
            InstanceRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Autowired
    public void setMultRegisterCenter(MultRegisterCenter multRegisterCenter) {
        this.multRegisterCenter = multRegisterCenter;
    }

    /**
     * 应用启动完Ready的事件
     *
     * @param event
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        Map<String, ConsulDiscoveryClient> map = multRegisterCenter.getMultConsulMap();
        map.entrySet().forEach(e -> {
            consulDiscoveryClient.set(e.getValue());
            discover();
            consulDiscoveryClient.remove();
        });
    }

    @EventListener
    public void registerCenterRefresh(RegisterCenterRefreshEvent event) {
        Map<String, ConsulDiscoveryClient> map = multRegisterCenter.getMultConsulMap();
        map.entrySet().forEach(e -> {
            consulDiscoveryClient.set(e.getValue());
            discover();
            consulDiscoveryClient.remove();
        });
    }

    @EventListener
    public void onInstanceRegistered(InstanceRegisteredEvent<?> event) {
        Map<String, ConsulDiscoveryClient> map = multRegisterCenter.getMultConsulMap();
        map.entrySet().forEach(e -> {
            consulDiscoveryClient.set(e.getValue());
            discover();
            consulDiscoveryClient.remove();
        });
    }

    @EventListener
    public void onParentHeartbeat(ParentHeartbeatEvent event) {
        HeartbeatMonitor heartbeatMonitor = getHeartbeatMonitorByClient((ConsulDiscoveryClient) event.getSource());
        threadLocalmonitor.set(heartbeatMonitor);
        discoverIfNeeded((ConsulDiscoveryClient) event.getSource(), event.getValue());
        threadLocalmonitor.remove();
    }

    @EventListener
    public void onApplicationEvent(HeartbeatEvent event) {
        Map<String, ConsulDiscoveryClient> map = multRegisterCenter.getMultConsulMap();
        map.entrySet().forEach(e -> {
            HeartbeatMonitor heartbeatMonitor = getHeartbeatMonitorByClient(e.getValue());
            threadLocalmonitor.set(heartbeatMonitor);
            discoverIfNeeded(e.getValue(), event.getValue());
            threadLocalmonitor.remove();
        });

    }


    private void discoverIfNeeded(ConsulDiscoveryClient source, Object value) {
        if (threadLocalmonitor.get().update(value)) {
            consulDiscoveryClient.set(source);
            discover();
            consulDiscoveryClient.remove();
        }
    }

    protected void discover() {
        Flux.fromIterable(consulDiscoveryClient.get().getServices())
                .filter(this::shouldRegisterService)
                .flatMapIterable(consulDiscoveryClient.get()::getInstances)
                .flatMap(this::registerInstance)
                .collect(Collectors.toSet())
                .flatMap(this::removeStaleInstances)
                .subscribe(v -> {
                }, ex -> log.error("Unexpected error.", ex));
    }


    /**
     * 多注册中心根据注册中心标识分别移除不同注册中心过时的数据
     *
     * @param registeredInstanceIds
     * @return
     */
    protected Mono<Void> removeStaleInstances(Set<InstanceId> registeredInstanceIds) {
        return repository.findAll()
                .filter(Instance::isRegistered)
                .filter(instance -> instance.getRegistration().getSource().
                        equals(getCodeByClient(consulDiscoveryClient.get())))
                .map(Instance::getId)
                .filter(id -> !registeredInstanceIds.contains(id))
                .doOnNext(id -> log.info("Instance ({}) missing in DiscoveryClient services ", id))
                .flatMap(registry::deregister)
                .then();
    }

    private String getCodeByClient(ConsulDiscoveryClient client) {
        return multRegisterCenter.getMultConsulCodeMap().get(client);
    }

    private HeartbeatMonitor getHeartbeatMonitorByClient(ConsulDiscoveryClient client) {
        return multRegisterCenter.getMultHeartbeatMonitorMap().get(client);
    }


    protected boolean shouldRegisterService(final String serviceId) {
        boolean shouldRegister = matchesPattern(serviceId, services) && !matchesPattern(serviceId, ignoredServices);
        if (!shouldRegister) {
            log.debug("Ignoring discovered service {}", serviceId);
        }
        return shouldRegister;
    }

    protected boolean matchesPattern(String serviceId, Set<String> patterns) {
        return patterns.stream().anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, serviceId));
    }

    protected Mono<InstanceId> registerInstance(ServiceInstance instance) {
        try {
            Registration registration = converter.convert(instance).toBuilder().
                    source(getCodeByClient(consulDiscoveryClient.get())).build();
            log.debug("Registering discovered instance {}", registration);
            return registry.register(registration);
        } catch (Exception ex) {
            log.error("Couldn't register instance for service {}", instance, ex);
        }
        return Mono.empty();
    }

    public void setConverter(ServiceInstanceConverter converter) {
        this.converter = converter;
    }

    public Set<String> getIgnoredServices() {
        return ignoredServices;
    }

    public void setIgnoredServices(Set<String> ignoredServices) {
        this.ignoredServices = ignoredServices;
    }

    public Set<String> getServices() {
        return services;
    }

    public void setServices(Set<String> services) {
        this.services = services;
    }
}
