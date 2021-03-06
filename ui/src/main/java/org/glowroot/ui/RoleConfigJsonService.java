/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.ImmutableRoleConfig;
import org.glowroot.common.config.PermissionParser;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.CannotDeleteLastRoleException;
import org.glowroot.common.repo.ConfigRepository.DuplicateRoleNameException;
import org.glowroot.common.util.ObjectMappers;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class RoleConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(RoleConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<RoleConfig> orderingByName = new Ordering<RoleConfig>() {
        @Override
        public int compare(RoleConfig left, RoleConfig right) {
            return left.name().compareToIgnoreCase(right.name());
        }
    };

    private final boolean embedded;
    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;

    RoleConfigJsonService(boolean embedded, ConfigRepository configRepository,
            AgentRepository agentRepository) {
        this.embedded = embedded;
        this.configRepository = configRepository;
        this.agentRepository = agentRepository;
    }

    @GET(path = "/backend/admin/roles", permission = "admin:view:role")
    String getRoleConfig(@BindRequest RoleConfigRequest request) throws Exception {
        Optional<String> name = request.name();
        if (name.isPresent()) {
            return getRoleConfigInternal(name.get());
        } else {
            List<RoleConfigListDto> responses = Lists.newArrayList();
            List<RoleConfig> roleConfigs = configRepository.getRoleConfigs();
            roleConfigs = orderingByName.immutableSortedCopy(roleConfigs);
            for (RoleConfig roleConfig : roleConfigs) {
                responses.add(RoleConfigListDto.create(roleConfig));
            }
            return mapper.writeValueAsString(responses);
        }
    }

    @GET(path = "/backend/admin/all-agent-rollups", permission = "admin:edit:role")
    String getAllAgentRollups() throws Exception {
        return mapper.writeValueAsString(getFlattenedAgentRollups());
    }

    @POST(path = "/backend/admin/roles/add", permission = "admin:edit:role")
    String addRole(@BindRequest RoleConfigDto roleConfigDto) throws Exception {
        RoleConfig roleConfig = roleConfigDto.convert(embedded);
        try {
            configRepository.insertRoleConfig(roleConfig);
        } catch (DuplicateRoleNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "name");
        }
        return getRoleConfigInternal(roleConfig.name());
    }

    @POST(path = "/backend/admin/roles/update", permission = "admin:edit:role")
    String updateRole(@BindRequest RoleConfigDto roleConfigDto) throws Exception {
        RoleConfig roleConfig = roleConfigDto.convert(embedded);
        String version = roleConfigDto.version().get();
        try {
            configRepository.updateRoleConfig(roleConfig, version);
        } catch (DuplicateRoleNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "name");
        }
        return getRoleConfigInternal(roleConfig.name());
    }

    @POST(path = "/backend/admin/roles/remove", permission = "admin:edit:role")
    String removeRole(@BindRequest RoleConfigRequest request) throws Exception {
        try {
            configRepository.deleteRoleConfig(request.name().get());
        } catch (CannotDeleteLastRoleException e) {
            return "{\"errorCannotDeleteLastRole\":true}";
        }
        return "{}";
    }

    private String getRoleConfigInternal(String name) throws Exception {
        RoleConfig roleConfig = configRepository.getRoleConfig(name);
        if (roleConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        ImmutableRoleConfigResponse.Builder response = ImmutableRoleConfigResponse.builder()
                .config(RoleConfigDto.create(roleConfig, embedded));
        if (!embedded) {
            response.allAgentRollups(getFlattenedAgentRollups());
        }
        return mapper.writeValueAsString(response.build());
    }

    private List<FlattenedAgentRollup> getFlattenedAgentRollups() {
        List<AgentRollup> agentRollups = agentRepository.readAgentRollups();
        List<FlattenedAgentRollup> flattenedAgentRollups = Lists.newArrayList();
        for (AgentRollup agentRollup : agentRollups) {
            flattenedAgentRollups.addAll(getFlattenedAgentRollups(agentRollup, 0));
        }
        return flattenedAgentRollups;
    }

    private static List<FlattenedAgentRollup> getFlattenedAgentRollups(AgentRollup agentRollup,
            int depth) {
        List<FlattenedAgentRollup> flattenedAgentRollups = Lists.newArrayList();
        flattenedAgentRollups.add(ImmutableFlattenedAgentRollup.builder()
                .name(agentRollup.id())
                .depth(depth)
                .build());
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            flattenedAgentRollups.addAll(getFlattenedAgentRollups(childAgentRollup, depth + 1));
        }
        return flattenedAgentRollups;
    }

    @Value.Immutable
    interface RoleConfigRequest {
        Optional<String> name();
    }

    @Value.Immutable
    interface RoleConfigResponse {
        RoleConfigDto config();
        ImmutableList<FlattenedAgentRollup> allAgentRollups();
    }

    @Value.Immutable
    interface FlattenedAgentRollup {
        int depth();
        String name();
    }

    @Value.Immutable
    abstract static class RoleConfigListDto {

        abstract String name();
        abstract String version();

        private static RoleConfigListDto create(RoleConfig roleConfig) {
            return ImmutableRoleConfigListDto.builder()
                    .name(roleConfig.name())
                    .version(roleConfig.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class RoleConfigDto {

        abstract String name();
        abstract ImmutableList<String> permissions();
        abstract ImmutableList<ImmutableRolePermissionBlock> permissionBlocks();
        abstract Optional<String> version(); // absent for insert operations

        private RoleConfig convert(boolean embedded) {
            ImmutableRoleConfig.Builder builder = ImmutableRoleConfig.builder()
                    .embedded(embedded)
                    .name(name());
            if (embedded) {
                builder.addAllPermissions(permissions());
            } else {
                for (String permission : permissions()) {
                    if (permission.startsWith("agent:")) {
                        builder.addPermissions(
                                "agent:*:" + permission.substring("agent:".length()));
                    } else {
                        builder.addPermissions(permission);
                    }
                }
            }
            for (RolePermissionBlock permissionBlock : permissionBlocks()) {
                String agentIds =
                        PermissionParser.quoteIfNecessaryAndJoin(permissionBlock.agentRollups());
                for (String permission : permissionBlock.permissions()) {
                    builder.addPermissions(
                            "agent:" + agentIds + ":" + permission.substring("agent:".length()));
                }
            }
            return builder.build();
        }

        private static RoleConfigDto create(RoleConfig roleConfig, boolean embedded) {
            ImmutableRoleConfigDto.Builder builder = ImmutableRoleConfigDto.builder()
                    .name(roleConfig.name());
            if (embedded) {
                builder.addAllPermissions(roleConfig.permissions());
            } else {
                Multimap<List<String>, String> permissionBlocks = HashMultimap.create();
                for (String permission : roleConfig.permissions()) {
                    if (permission.startsWith("agent:")) {
                        PermissionParser parser = new PermissionParser(permission);
                        parser.parse();
                        if (parser.getAgentIds().size() == 1
                                && parser.getAgentIds().get(0).equals("*")) {
                            builder.addPermissions(parser.getPermission());
                        } else {
                            // sorting in order to combine agent:a,b:... and agent:b,a:...
                            List<String> agentIds =
                                    Ordering.natural().sortedCopy(parser.getAgentIds());
                            permissionBlocks.put(agentIds, parser.getPermission());
                        }
                    } else {
                        builder.addPermissions(permission);
                    }
                }
                for (Entry<List<String>, Collection<String>> entry : permissionBlocks.asMap()
                        .entrySet()) {
                    builder.addPermissionBlocks(ImmutableRolePermissionBlock.builder()
                            .addAllAgentRollups(entry.getKey())
                            .addAllPermissions(entry.getValue())
                            .build());
                }
            }
            return builder.version(roleConfig.version())
                    .build();
        }
    }

    @Value.Immutable
    interface RolePermissionBlock {
        ImmutableList<String> agentRollups();
        ImmutableList<String> permissions();
    }
}
