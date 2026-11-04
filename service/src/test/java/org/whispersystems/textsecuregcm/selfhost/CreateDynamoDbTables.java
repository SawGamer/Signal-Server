/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtension;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;

/**
 * Creates every DynamoDB table the chat server needs in a DynamoDB-compatible database (e.g. DynamoDB Local). Safe to
 * run repeatedly: existing tables are left alone.
 * <p>
 * Lives in the test sources on purpose: it reuses {@link DynamoDbExtensionSchema}, the table definitions Signal
 * maintains for its own integration tests, so new upstream tables are picked up automatically. Table names are the
 * ones in that schema (and in {@code config/test.yml}), e.g. {@code accounts_test}.
 * <p>
 * Usage: {@code java -cp TextSecureServer.jar:TextSecureServer-tests.jar
 * org.whispersystems.textsecuregcm.selfhost.CreateDynamoDbTables http://dynamodb:8000}
 */
public class CreateDynamoDbTables {

  private static final ProvisionedThroughput THROUGHPUT = ProvisionedThroughput.builder()
      .readCapacityUnits(20L)
      .writeCapacityUnits(20L)
      .build();

  public static void main(final String[] args) {
    if (args.length != 1) {
      System.err.println("usage: CreateDynamoDbTables <endpoint, e.g. http://dynamodb:8000>");
      System.exit(2);
    }

    try (final DynamoDbClient client = DynamoDbClient.builder()
        .endpointOverride(URI.create(args[0]))
        .region(Region.of("local"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        .build()) {

      final Set<String> existing = listTables(client);
      int created = 0;

      for (final DynamoDbExtension.TableSchema table : DynamoDbExtensionSchema.Tables.values()) {
        if (existing.contains(table.tableName())) {
          continue;
        }

        final KeySchemaElement hashKey = KeySchemaElement.builder()
            .attributeName(table.hashKeyName()).keyType(KeyType.HASH).build();
        final KeySchemaElement[] keySchema = table.rangeKeyName() == null
            ? new KeySchemaElement[]{hashKey}
            : new KeySchemaElement[]{hashKey, KeySchemaElement.builder()
                .attributeName(table.rangeKeyName()).keyType(KeyType.RANGE).build()};

        client.createTable(CreateTableRequest.builder()
            .tableName(table.tableName())
            .keySchema(keySchema)
            .attributeDefinitions(table.attributeDefinitions().isEmpty() ? null : table.attributeDefinitions())
            .globalSecondaryIndexes(table.globalSecondaryIndexes().isEmpty() ? null : table.globalSecondaryIndexes())
            .localSecondaryIndexes(table.localSecondaryIndexes().isEmpty() ? null : table.localSecondaryIndexes())
            .provisionedThroughput(THROUGHPUT)
            .build());

        System.out.println("created " + table.tableName());
        created++;
      }

      System.out.printf("DynamoDB tables ready (%d created, %d already existed)%n", created, existing.size());
    }
  }

  private static Set<String> listTables(final DynamoDbClient client) {
    final Set<String> names = new HashSet<>();
    String start = null;
    do {
      final ListTablesResponse response = client.listTables(ListTablesRequest.builder()
          .exclusiveStartTableName(start)
          .build());
      names.addAll(response.tableNames());
      start = response.lastEvaluatedTableName();
    } while (start != null);
    return names;
  }
}
