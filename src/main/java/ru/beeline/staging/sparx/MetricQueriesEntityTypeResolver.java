package ru.beeline.staging.sparx;

import ru.beeline.staging.sparx.dto.MetricQueriesSourceMeta;

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
