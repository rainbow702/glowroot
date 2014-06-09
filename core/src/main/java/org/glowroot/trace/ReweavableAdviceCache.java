/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.trace;

import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.glowroot.config.PointcutConfig;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.Advice;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// only for pointcuts from config.json, not from plugins
@ThreadSafe
public class ReweavableAdviceCache {

    private volatile ImmutableList<Advice> advisors;
    private volatile ImmutableSet<String> pointcutConfigVersions;

    ReweavableAdviceCache(ImmutableList<PointcutConfig> pointcutConfigs) {
        advisors =
                ImmutableList.copyOf(DynamicAdviceGenerator.createAdvisors(pointcutConfigs, null));
        pointcutConfigVersions = ImmutableSet.copyOf(createVersionSet(pointcutConfigs));
    }

    Supplier<ImmutableList<Advice>> getAdvisorsSupplier() {
        return new Supplier<ImmutableList<Advice>>() {
            @Override
            public ImmutableList<Advice> get() {
                return advisors;
            }
        };
    }

    public void updateAdvisors(ImmutableList<PointcutConfig> pointcutConfigs) {
        advisors =
                ImmutableList.copyOf(DynamicAdviceGenerator.createAdvisors(pointcutConfigs, null));
        pointcutConfigVersions = ImmutableSet.copyOf(createVersionSet(pointcutConfigs));
    }

    public boolean isOutOfSync(ImmutableList<PointcutConfig> pointcutConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            versions.add(pointcutConfig.getVersion());
        }
        return !versions.equals(this.pointcutConfigVersions);
    }

    private static Set<String> createVersionSet(List<PointcutConfig> pointcutConfigs) {
        Set<String> pointcutConfigVersions = Sets.newHashSet();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutConfigVersions.add(pointcutConfig.getVersion());
        }
        return pointcutConfigVersions;
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}