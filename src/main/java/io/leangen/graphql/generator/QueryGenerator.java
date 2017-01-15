package io.leangen.graphql.generator;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.Scalars;
import graphql.relay.Relay;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import io.leangen.graphql.generator.mapping.TypeMapper;
import io.leangen.graphql.metadata.Query;
import io.leangen.graphql.metadata.QueryArgument;
import io.leangen.graphql.metadata.QueryArgumentDefaultValue;
import io.leangen.graphql.metadata.strategy.input.InputDeserializer;
import io.leangen.graphql.query.ExecutionContext;
import io.leangen.graphql.query.IdTypeMapper;

import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInputObjectField.newInputObjectField;

/**
 * <p>Drives the work of mapping Java structures into their GraphQL representations.</p>
 * While the task of mapping types is delegated to instances of {@link io.leangen.graphql.generator.mapping.TypeMapper},
 * selection of mappers, construction and attachment of resolvers (modeled by {@link DataFetcher}s
 * in <a href=https://github.com/graphql-java/graphql-java>graphql-java</a>), and other universal tasks are encapsulated
 * by this class.
 */
public class QueryGenerator {

    private List<GraphQLFieldDefinition> queries; //The list of all mapped queries
    private List<GraphQLFieldDefinition> mutations; //The list of all mapped mutations
    public GraphQLInterfaceType node; //Node interface, as defined by the Relay GraphQL spec

    private static final String RELAY_ID = "id"; //The name of the ID field, as defined by the Node interface

    /**
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     */
    public QueryGenerator(BuildContext buildContext) {
        this.node = buildContext.relay.nodeInterface(new RelayNodeTypeResolver());
        this.queries = generateQueries(buildContext);
        this.mutations = generateMutations(buildContext);
    }

    /**
     * Generates {@link GraphQLFieldDefinition}s representing all top-level queries (with their types, arguments and sub-queries)
     * fully mapped. This is the entry point into the query-mapping logic.
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return A list of {@link GraphQLFieldDefinition}s representing all top-level queries
     */
    private List<GraphQLFieldDefinition> generateQueries(BuildContext buildContext) {
        Collection<Query> rootQueries = buildContext.queryRepository.getQueries();

        List<GraphQLFieldDefinition> queries = new ArrayList<>(rootQueries.size() + 1);
        Map<String, Query> nodeQueriesByType = new HashMap<>();

        for (Query query : rootQueries) {
            GraphQLFieldDefinition graphQlQuery = toGraphQLQuery(query, null, buildContext);
            queries.add(graphQlQuery);

            nodeQueriesByType.put(graphQlQuery.getType().getName(), query);
        }

        //TODO Shouldn't this check if the return type has relayID? Also, why add queries without primary resolver?
        //Add support for Relay Node query only if Relay-enabled resolvers exist
        if (nodeQueriesByType.values().stream().anyMatch(Query::hasPrimaryResolver)) {
            queries.add(buildContext.relay.nodeField(node, createNodeResolver(nodeQueriesByType, buildContext.relay, buildContext.executionContext)));
        }
        return queries;
    }

    /**
     * Generates {@link GraphQLFieldDefinition}s representing all mutations (with their types, arguments and sub-queries)
     * fully mapped. By the GraphQL spec, all mutations are top-level only.
     * This is the entry point into the mutation-mapping logic.
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return A list of {@link GraphQLFieldDefinition}s representing all mutations
     */
    private List<GraphQLFieldDefinition> generateMutations(BuildContext buildContext) {
        Collection<Query> mutations = buildContext.queryRepository.getMutations();
        return mutations.stream()
                .map(mutation -> toGraphQLQuery(mutation, null, buildContext))
                .collect(Collectors.toList());
    }

    /**
     * Maps a single query to a GraphQL output field (as queries in GraphQL are nothing but fields of the root query type).
     *
     * @param query The query to map to a GraphQL output field
     * @param enclosingTypeName The name of the GraphQL output type this field belongs to
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL output field representing the given query
     */
    public GraphQLFieldDefinition toGraphQLQuery(Query query, String enclosingTypeName, BuildContext buildContext) {
        Set<Type> abstractTypes = new HashSet<>();
        GraphQLOutputType type = toGraphQLType(query, abstractTypes, buildContext);
        GraphQLFieldDefinition.Builder queryBuilder = newFieldDefinition()
                .name(query.getName())
                .description(query.getName())
                .type(type);
        addArguments(queryBuilder, query, abstractTypes, buildContext);
        if (type.equals(Scalars.GraphQLID)) {
            queryBuilder.dataFetcher(createRelayIdResolver(query, enclosingTypeName, buildContext.relay, buildContext.idTypeMapper, buildContext.executionContext));
        } else {
            queryBuilder.dataFetcher(createResolver(query, buildContext.inputDeserializerFactory.getDeserializer(abstractTypes), buildContext.executionContext));
        }

        return queryBuilder.build();
    }

    /**
     * Maps the (Java) return type of the given query to a GraphQL output type, taking care to map
     * {@link io.leangen.graphql.query.relay.Page} to a Relay GraphQL spec compliant connection type.
     * <p>Delegates most of the work to {@link #toGraphQLInputType(AnnotatedType, Set, BuildContext)}.</p>
     *
     * @param query The query whose return type is to be mapped to a GraphQL output type
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL type representing the return type of the given query
     */
    private GraphQLOutputType toGraphQLType(Query query, Set<Type> abstractTypes, BuildContext buildContext) {
        GraphQLOutputType type = toGraphQLType(query.getJavaType(), abstractTypes, buildContext);
        if (query.isPageable()) {
            GraphQLObjectType edge = buildContext.relay.edgeType(type.getName(), type, null, Collections.emptyList());
            type = buildContext.relay.connectionType(type.getName(), edge, Collections.emptyList());
        }
        return type;
    }

    /**
     * Maps a Java type to a GraphQL output type. Delegates most of the work to applicable
     * {@link io.leangen.graphql.generator.mapping.TypeMapper}s.
     * <p>See {@link TypeMapper#toGraphQLType(AnnotatedType, Set, QueryGenerator, BuildContext)}</p>
     *
     * @param javaType The Java type that is to be mapped to a GraphQL output type
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL output type corresponding to the given Java type
     */
    public GraphQLOutputType toGraphQLType(AnnotatedType javaType, Set<Type> abstractTypes, BuildContext buildContext) {
        return buildContext.typeMappers.getTypeMapper(javaType).toGraphQLType(javaType, abstractTypes, this, buildContext);
    }

    /**
     * Maps a single query (representing a ('getter') method on a domain object) to a GraphQL input field.
     *
     * @param query The query to map to a GraphQL input field
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL input field representing the given query
     */
    public GraphQLInputObjectField toGraphQLInputField(Query query, Set<Type> abstractTypes, BuildContext buildContext) {
        GraphQLInputObjectField.Builder builder = newInputObjectField()
                .name(query.getName())
                .description(query.getDescription())
                .type(toGraphQLInputType(query.getJavaType(), abstractTypes, buildContext));
        return builder.build();
    }

    /**
     * Maps a Java type to a GraphQL input type. Delegates most of the work to applicable
     * {@link io.leangen.graphql.generator.mapping.TypeMapper}s.
     * <p>See {@link TypeMapper#toGraphQLInputType(AnnotatedType, Set, QueryGenerator, BuildContext)}</p>
     *
     * @param javaType The Java type that is to be mapped to a GraphQL input type
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL input type corresponding to the given Java type
     */
    public GraphQLInputType toGraphQLInputType(AnnotatedType javaType, Set<Type> abstractTypes, BuildContext buildContext) {
        TypeMapper mapper = buildContext.typeMappers.getTypeMapper(javaType);
        return mapper.toGraphQLInputType(javaType, abstractTypes, this, buildContext);
    }

    /**
     * Maps query arguments and adds them to the provided query builder.
     *
     * @param queryBuilder The query builder to which the mapped arguments are to be added
     * @param query The query whose arguments are to be mapped
     * @param buildContext The shared context containing all the global information needed for mapping
     */
    private void addArguments(GraphQLFieldDefinition.Builder queryBuilder, Query query, Set<Type> abstractTypes, BuildContext buildContext) {
        query.getArguments()
                .forEach(argument -> queryBuilder.argument(toGraphQLArgument(argument, abstractTypes, buildContext)));
        if (query.isPageable()) {
            queryBuilder.argument(buildContext.relay.getConnectionFieldArguments());
        }
    }

    private GraphQLArgument toGraphQLArgument(QueryArgument queryArgument, Set<Type> abstractTypes, BuildContext buildContext) {
        Set<Type> argumentAbstractTypes = new HashSet<>();
        GraphQLArgument.Builder argument = newArgument()
                .name(queryArgument.getName())
                .description(queryArgument.getDescription())
                .type(toGraphQLInputType(queryArgument.getJavaType(), argumentAbstractTypes, buildContext));

        abstractTypes.addAll(argumentAbstractTypes);
        QueryArgumentDefaultValue defaultValue = queryArgument.getDefaultValue();
        if (defaultValue.isPresent()) {
            argument.defaultValue(defaultValue.get());
        }
        return argument.build();
    }

    /**
     * Creates a generic resolver for the given query.
     * @implSpec This resolver simply invokes {@link Query#resolve(DataFetchingEnvironment, InputDeserializer, ExecutionContext)}
     *
     * @param query The query for which the resolver is being created
     * @param executionContext The shared context containing all the global information needed for query resolution
     *
     * @return The resolver for the given query
     */
    private DataFetcher createResolver(Query query, InputDeserializer inputDeserializer, ExecutionContext executionContext) {
        return env -> query.resolve(env, inputDeserializer, executionContext);
    }

    /**
     * Creates a resolver for the <em>node</em> query as defined by the Relay GraphQL spec.
     * <p>This query only takes a singe argument called "id" of type String, and returns the object implementing the
     * <em>Node</em> interface to which the given id corresponds.</p>
     *
     * @param nodeQueriesByType A map of all queries whose return types implement the <em>Node</em> interface, keyed
     *                          by their corresponding GraphQL type name
     * @param relay Relay helper
     * @param executionContext The shared context containing all the global information needed for query resolution
     *
     * @return The node query resolver
     */
    //TODO should this maybe just delegate?
    //e.g. return ((GraphQLObjectType)env.getGraphQLSchema().getType("")).getFieldDefinition("").getDataFetcher().get(env);
    private DataFetcher createNodeResolver(Map<String, Query> nodeQueriesByType, Relay relay, ExecutionContext executionContext) {
        return env -> {
            String typeName;
            try {
                typeName = relay.fromGlobalId((String) env.getArguments().get(RELAY_ID)).getType();
            } catch (Exception e) {
                throw new IllegalArgumentException(env.getArguments().get(RELAY_ID) + " is not a valid Relay node ID");
            }
            if (!nodeQueriesByType.containsKey(typeName)) {
                throw new IllegalArgumentException(typeName + " is not a known type");
            }
            if (!nodeQueriesByType.get(typeName).hasPrimaryResolver()) {
                throw new IllegalArgumentException("Query '" + nodeQueriesByType.get(typeName).getName() + "' has no primary resolver");
            }
            return nodeQueriesByType.get(typeName).resolve(env, null, executionContext);
        };
    }

    /**
     * Similarly to {@link #createResolver(Query, InputDeserializer, ExecutionContext)}, this creates a resolver that delegates
     * to {@link Query#resolve(DataFetchingEnvironment, InputDeserializer, ExecutionContext)}, but it additionally encodes the result
     * into a a string suitable to be used as a Relay ID.
     *
     * @param query The query for which the resolver is being created
     * @param enclosingTypeName The name of the GraphQL output type this field belongs to
     * @param relay Relay helper
     * @param idTypeMapper Mapper used to encode the value return by {@link Query#resolve(DataFetchingEnvironment, InputDeserializer, ExecutionContext)}
     *                     into a string value suitable to be used as a Relay ID
     * @param executionContext The shared context containing all the global information needed for query resolution
     *
     * @return The Relay ID resolver for the given query
     */
    private DataFetcher createRelayIdResolver(Query query, String enclosingTypeName, Relay relay, IdTypeMapper idTypeMapper, ExecutionContext executionContext) {
        return env -> {
            Object id = query.resolve(env, null, executionContext);
            return relay.toGlobalId(enclosingTypeName, idTypeMapper.serialize(id));
        };
    }

    /**
     * Fetches all the mapped GraphQL fields representing top-level queries, ready to be attached to the root query type.
     *
     * @return A list of GraphQL fields representing top-level queries
     */
    public List<GraphQLFieldDefinition> getQueries() {
        return queries;
    }

    /**
     * Fetches all the mapped GraphQL fields representing mutations, ready to be attached to the root mutation type.
     *
     * @return A list of GraphQL fields representing mutations
     */
    public List<GraphQLFieldDefinition> getMutations() {
        return mutations;
    }
}
