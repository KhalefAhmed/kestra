package io.kestra.plugin.core.namespace;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.services.FlowService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List flows from a namespace."
)
@Plugin(
    examples = {
        @Example(
            title = "List all flows from a specific namespace.",
            full = true,
            code = {
                """
                id: list_namespace_flows
                namespace: company.team
                tasks:
                  - id: list_flows
                    type: io.kestra.plugin.core.namespace.ListFlows
                    namespace: company
                """
            }
        )
    }
)
public class ListFlows extends Task implements RunnableTask<ListFlows.Output> {
    @Schema(
        title = "The namespace to list flows from."
    )
    @PluginProperty(dynamic = true)
    private Property<String> namespace;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        FlowRepositoryInterface flowRepository = ((DefaultRunContext) runContext)
            .getApplicationContext()
            .getBean(FlowRepositoryInterface.class);

        String tenantId = runContext.flowInfo().tenantId();
        String resolvedNamespace = runContext.render(namespace).as(String.class).orElseThrow();

        FlowService flowService = ((DefaultRunContext) runContext).getApplicationContext().getBean(FlowService.class);
        flowService.checkAllowedNamespace(tenantId, resolvedNamespace, tenantId, runContext.flowInfo().namespace());

        List<Flow> flows = flowRepository.findByNamespace(tenantId, resolvedNamespace);

        logger.debug("{} flows found in namespace {}", flows.size(), resolvedNamespace);

        return Output.builder()
            .flows(flows)
            .build();
    }

    @SuperBuilder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "List of flows in the namespace."
        )
        private List<Flow> flows;
    }
}