package ru.beeline.staging.sparx;

import ru.beeline.staging.sparx.dto.MetricQueriesSourceMeta;

/**
 * Shared by MetricQueriesPreAdapter and MetricQueriesAdapter (which independently re-resolves the
 * same Sparx object by uid — see MetricQueriesAdapter javadoc). Priority: stereotype
 * (softwareSystem/C4_Container) over object_type (Interface); everything else falls back to
 * 'object' (preadapter-spec §5.3).
 */
public final class MetricQueriesEntityTypeResolver {

    private MetricQueriesEntityTypeResolver() {}

    public static String resolve(MetricQueriesSourceMeta row) {
        if ("softwareSystem".equals(row.getStereotype())) {
            return "product";
        }
        if ("C4_Container".equals(row.getStereotype())) {
            return "container";
        }
        if ("Interface".equals(row.getObjectType())) {
            return "interface";
        }
        return "object";
    }
}
