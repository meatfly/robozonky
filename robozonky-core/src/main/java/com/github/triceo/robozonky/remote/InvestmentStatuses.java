/*
 *
 *  * Copyright 2016 Lukáš Petrovický
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 * /
 */
package com.github.triceo.robozonky.remote;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InvestmentStatuses {

    public static InvestmentStatuses valueOf(final String statuses) {
        // trim the surrounding []
        final String trimmed = statuses.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Expecting string in the format of [A, B, C], got " + statuses);
        }
        if (trimmed.length() == 2) { // only contains []
            return InvestmentStatuses.of();
        }
        final String[] parts = trimmed.substring(1, trimmed.length() - 1).split("\\Q,\\E");
        if (parts.length == 1 && parts[0].trim().length() == 0) { // only contains whitespace
            return InvestmentStatuses.of();
        }
        // trim the parts
        final Collection<String> strings = Stream.of(parts).map(String::trim).collect(Collectors.toList());
        // convert string representations to actual instances
        final Collection<InvestmentStatus> converted =
                strings.stream().map(InvestmentStatus::valueOf).collect(Collectors.toList());
        return InvestmentStatuses.of(converted);
    }

    public static InvestmentStatuses of(final InvestmentStatus... statuses) {
        return of(Arrays.asList(statuses));
    }

    public static InvestmentStatuses of(final Collection<InvestmentStatus> statuses) {
        return new InvestmentStatuses(statuses);
    }

    public static InvestmentStatuses all() {
        return of(InvestmentStatus.values());
    }

    private final Set<InvestmentStatus> statuses;

    private InvestmentStatuses(final Collection<InvestmentStatus> statuses) {
        this.statuses = statuses.isEmpty() ? Collections.emptySet() : EnumSet.copyOf(statuses);
    }

    public Set<InvestmentStatus> getInvestmentStatuses() {
        return Collections.unmodifiableSet(statuses);
    }

    @Override
    public String toString() {
        return statuses.stream().collect(Collectors.mapping(InvestmentStatus::name, Collectors.joining(", ", "[", "]")));
    }
}
