package io.leangen.graphql.util;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import graphql.schema.GraphQLScalarType;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.graphql.metadata.QueryResolver;
import io.leangen.graphql.metadata.strategy.query.AnnotatedResolverExtractor;
import io.leangen.graphql.query.DefaultIdTypeMapper;
import io.leangen.graphql.query.IdTypeMapper;
import io.leangen.graphql.query.relay.CursorProvider;
import io.leangen.graphql.query.relay.Edge;
import io.leangen.graphql.query.relay.Page;
import io.leangen.graphql.query.relay.generic.GenericEdge;
import io.leangen.graphql.query.relay.generic.GenericPage;
import io.leangen.graphql.query.relay.generic.GenericPageInfo;

import static graphql.Scalars.GraphQLBigDecimal;
import static graphql.Scalars.GraphQLBigInteger;
import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLByte;
import static graphql.Scalars.GraphQLChar;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLLong;
import static graphql.Scalars.GraphQLShort;
import static graphql.Scalars.GraphQLString;
import static io.leangen.graphql.util.Scalars.GraphQLISODate;
import static io.leangen.graphql.util.Scalars.GraphQLUri;
import static io.leangen.graphql.util.Scalars.GraphQLUuid;

/**
 * Created by bojan.tomic on 3/4/16.
 */
public class GraphQLUtils {

    public static final String BASIC_INTROSPECTION_QUERY = "{ __schema { queryType { name fields { name type { name kind ofType { name kind fields { name } }}}}}}";

    public static final String FULL_INTROSPECTION_QUERY = "query IntrospectionQuery { __schema { queryType { name } mutationType { name } types { ...FullType } directives { name description args { ...InputValue } onOperation onFragment onField } } } fragment FullType on __Type { kind name description fields(includeDeprecated: true) { name description args { ...InputValue } type { ...TypeRef } isDeprecated deprecationReason } inputFields { ...InputValue } interfaces { ...TypeRef } enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason } possibleTypes { ...TypeRef } } fragment InputValue on __InputValue { name description type { ...TypeRef } defaultValue } fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }";

    public static final Map<Type, GraphQLScalarType> scalars = getScalarMapping();

    public static final IdTypeMapper defaultIdMapper = new DefaultIdTypeMapper();

    public static GraphQLScalarType toGraphQLScalarType(Type javaType) {
        return scalars.get(javaType);
    }

    public static boolean isScalar(Type javaType) {
        return scalars.containsKey(javaType);
    }

    public static <N> Page<N> createIdBasedPage(List<N> nodes, String idFieldName, boolean hasNextPage, boolean hasPreviousPage) {
        return createIdBasedPage(nodes, idFieldName, new DefaultIdTypeMapper(), hasNextPage, hasPreviousPage);
    }

    public static <N> Page<N> createIdBasedPage(List<N> nodes, boolean hasNextPage, boolean hasPreviousPage) {
        return createIdBasedPage(nodes, new DefaultIdTypeMapper(), hasNextPage, hasPreviousPage);
    }

    public static <N> Page<N> createIdBasedPage(List<N> nodes, IdTypeMapper idTypeMapper, boolean hasNextPage, boolean hasPreviousPage) {
        return createIdBasedPage(nodes, getIdFieldName(nodes.get(0).getClass()), idTypeMapper, hasNextPage, hasPreviousPage);
    }

    public static <N> Page<N> createIdBasedPage(List<N> nodes, String idFieldName, IdTypeMapper idTypeMapper, boolean hasNextPage, boolean hasPreviousPage) {
        return createPage(nodes, (node, index) -> idTypeMapper.serialize(ClassUtils.getFieldValue(node, idFieldName)), hasNextPage, hasPreviousPage);
    }

    public static <N, I extends Comparable<? super I>> Page<N> createIdBasedPage(List<N> nodes, I globalMinId, I globalMaxId) {
        return createIdBasedPage(nodes, globalMinId, globalMaxId, Comparator.naturalOrder(), defaultIdMapper);
    }

    public static <N, I> Page<N> createIdBasedPage(List<N> nodes, I globalMinId, I globalMaxId, Comparator<I> comparator, IdTypeMapper idTypeMapper) {
        return createIdBasedPage(nodes, getIdFieldName(nodes.get(0).getClass()), globalMinId, globalMaxId, comparator, idTypeMapper);
    }

    public static <N, I> Page<N> createIdBasedPage(List<N> nodes, String idFieldName, I globalMinId, I globalMaxId, Comparator<I> comparator, IdTypeMapper idTypeMapper) {
        boolean hasNextPage = comparator.compare(ClassUtils.getFieldValue(nodes.get(nodes.size() - 1), idFieldName), globalMaxId) < 0;
        boolean hasPreviousPage = comparator.compare(ClassUtils.getFieldValue(nodes.get(0), idFieldName), globalMinId) > 0;

        return createIdBasedPage(nodes, idFieldName, idTypeMapper, hasNextPage, hasPreviousPage);
    }

    public static <N> Page<N> createOffsetBasedPage(List<N> nodes, long count, long offset) {
        return createOffsetBasedPage(nodes, offset, offset + nodes.size() < count, offset > 0 && count > 0);
    }

    public static <N> Page<N> createOffsetBasedPage(List<N> nodes, long offset, boolean hasNextPage, boolean hasPreviousPage) {
        return createPage(nodes, (node, index) -> Long.toString(offset + index + 1), hasNextPage, hasPreviousPage);
    }

    public static <N> Page<N> createPage(List<N> nodes, CursorProvider<N> cursorProvider, boolean hasNextPage, boolean hasPreviousPage) {
        List<Edge<N>> edges = createEdges(nodes, cursorProvider);
        return new GenericPage<>(edges, createPageInfo(edges, hasNextPage, hasPreviousPage));
    }

    public static <N> List<Edge<N>> createEdges(List<N> nodes, CursorProvider<N> cursorProvider) {
        List<Edge<N>> edges = new ArrayList<>(nodes.size());
        int index = 0;
        for (N node : nodes) {
            edges.add(new GenericEdge<>(node, cursorProvider.createCursor(node, index++)));
        }
        return edges;
    }

    public static <N> GenericPageInfo<N> createPageInfo(List<Edge<N>> edges, boolean hasNextPage, boolean hasPreviousPage) {
        return new GenericPageInfo<>(edges.get(0).getCursor(), edges.get(edges.size() - 1).getCursor(), hasNextPage, hasPreviousPage);
    }

    //TODO refactor this not to require QueryResolver construction, nor isRelayId, which should be removed
    private static <N> String getIdFieldName(Class<N> nodeType) {
        Collection<QueryResolver> resolvers = new AnnotatedResolverExtractor()
                .extractQueryResolvers(null, GenericTypeReflector.annotate(nodeType));
        Optional<QueryResolver> id = resolvers.stream().filter(QueryResolver::isRelayId).findFirst();
        if (id.isPresent()) {
            return id.get().getQueryName();
        }
        throw new IllegalStateException("ID field unknown for type " + nodeType.getName());
    }

    private static Map<Type, GraphQLScalarType> getScalarMapping() {
        Map<Type, GraphQLScalarType> scalarMapping = new HashMap<>();
        scalarMapping.put(Character.class, GraphQLChar);
        scalarMapping.put(char.class, GraphQLChar);
        scalarMapping.put(String.class, GraphQLString);
        scalarMapping.put(Byte.class, GraphQLByte);
        scalarMapping.put(byte.class, GraphQLByte);
        scalarMapping.put(Short.class, GraphQLShort);
        scalarMapping.put(short.class, GraphQLShort);
        scalarMapping.put(Integer.class, GraphQLInt);
        scalarMapping.put(int.class, GraphQLInt);
        scalarMapping.put(Long.class, GraphQLLong);
        scalarMapping.put(long.class, GraphQLLong);
        scalarMapping.put(Float.class, GraphQLFloat);
        scalarMapping.put(float.class, GraphQLFloat);
        scalarMapping.put(Double.class, GraphQLFloat);
        scalarMapping.put(double.class, GraphQLFloat);
        scalarMapping.put(BigInteger.class, GraphQLBigInteger);
        scalarMapping.put(BigDecimal.class, GraphQLBigDecimal);
        scalarMapping.put(Number.class, GraphQLBigDecimal);
        scalarMapping.put(Boolean.class, GraphQLBoolean);
        scalarMapping.put(boolean.class, GraphQLBoolean);
        scalarMapping.put(UUID.class, GraphQLUuid);
        scalarMapping.put(URI.class, GraphQLUri);
        scalarMapping.put(Date.class, GraphQLISODate);
        return Collections.unmodifiableMap(scalarMapping);
    }
}
