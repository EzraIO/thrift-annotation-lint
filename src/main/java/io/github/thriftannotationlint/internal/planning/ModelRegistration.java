package io.github.thriftannotationlint.internal.planning;

import io.github.thriftannotationlint.internal.model.SwiftModel;
import io.github.thriftannotationlint.internal.model.ThriftAnnotationDialect;

/** Cross-round model classification without retaining javac mirror objects. */
final class ModelRegistration {
    final String typeName;
    final SwiftModel.Kind kind;
    final ThriftAnnotationDialect dialect;

    ModelRegistration(
            String typeName,
            SwiftModel.Kind kind,
            ThriftAnnotationDialect dialect) {
        this.typeName = typeName;
        this.kind = kind;
        this.dialect = dialect;
    }

    String key() {
        return key(typeName, dialect);
    }

    static String key(String typeName, ThriftAnnotationDialect dialect) {
        return dialect.name() + "\u0000" + typeName;
    }
}
