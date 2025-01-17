package com.thatdot.quine.persistor.cassandra.aws

import java.net.InetSocketAddress
import java.util.Collections.singletonMap
import javax.net.ssl.SSLContext

import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.codahale.metrics.MetricRegistry
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlSession, CqlSessionBuilder, InvalidKeyspaceException}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder.createKeyspace
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.Region._
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
import software.aws.mcs.auth.SigV4AuthProvider

import com.thatdot.quine.model.QuineId
import com.thatdot.quine.persistor.cassandra.support.CassandraStatementSettings
import com.thatdot.quine.persistor.cassandra.{JournalsTableDefinition, SnapshotsTableDefinition}
import com.thatdot.quine.persistor.{PersistenceConfig, cassandra}
import com.thatdot.quine.util.AkkaStreams.distinct

/** Persistence implementation backed by AWS Keypaces.
  *
  * @param keyspace The keyspace the quine tables should live in.
  * @param replicationFactor
  * @param readConsistency
  * @param writeConsistency
  * @param writeTimeout How long to wait for a response when running an INSERT statement.
  * @param readTimeout How long to wait for a response when running a SELECT statement.
  * @param endpoints address(s) (host and port) of the Cassandra cluster to connect to.
  * @param localDatacenter If endpoints are specified, this argument is required. Default value on a new Cassandra install is 'datacenter1'.
  * @param shouldCreateTables Whether or not to create the required tables if they don't already exist.
  * @param shouldCreateKeyspace Whether or not to create the specified keyspace if it doesn't already exist. If it doesn't exist, it'll run {{{CREATE KEYSPACE IF NOT EXISTS `keyspace` WITH replication={'class':'SimpleStrategy','replication_factor':1}}}}
  */
class KeyspacesPersistor(
  persistenceConfig: PersistenceConfig,
  keyspace: String,
  awsRegion: Option[Region],
  readSettings: CassandraStatementSettings,
  writeTimeout: FiniteDuration,
  shouldCreateTables: Boolean,
  shouldCreateKeyspace: Boolean,
  metricRegistry: Option[MetricRegistry],
  snapshotPartMaxSizeBytes: Int
)(implicit
  materializer: Materializer
) extends cassandra.CassandraPersistor(
      persistenceConfig,
      keyspace,
      readSettings,
      CassandraStatementSettings(
        ConsistencyLevel.LOCAL_QUORUM, // Write consistency fixed by AWS Keyspaces
        writeTimeout
      ),
      shouldCreateTables,
      shouldCreateKeyspace,
      snapshotPartMaxSizeBytes
    ) {

  protected val journalsTableDef: JournalsTableDefinition = Journals
  protected val snapshotsTableDef: SnapshotsTableDefinition = Snapshots
  override def enumerateJournalNodeIds(): Source[QuineId, NotUsed] = super.enumerateJournalNodeIds().via(distinct)
  override def enumerateSnapshotNodeIds(): Source[QuineId, NotUsed] = super.enumerateSnapshotNodeIds().via(distinct)

  private val region: Region = awsRegion getOrElse new DefaultAwsRegionProviderChain().getRegion

  // From https://docs.aws.amazon.com/keyspaces/latest/devguide/programmatic.endpoints.html
  private val keyspacesEndpoints: Map[Region, String] = Map(
    US_EAST_2 -> "cassandra.us-east-2.amazonaws.com",
    US_EAST_1 -> "cassandra.us-east-1.amazonaws.com",
    US_WEST_1 -> "cassandra.us-west-1.amazonaws.com",
    US_WEST_2 -> "cassandra.us-west-2.amazonaws.com",
    AP_EAST_1 -> "cassandra.ap-east-1.amazonaws.com",
    AP_SOUTH_1 -> "cassandra.ap-south-1.amazonaws.com",
    AP_NORTHEAST_2 -> "cassandra.ap-northeast-2.amazonaws.com",
    AP_SOUTHEAST_1 -> "cassandra.ap-southeast-1.amazonaws.com",
    AP_SOUTHEAST_2 -> "cassandra.ap-southeast-2.amazonaws.com",
    AP_NORTHEAST_1 -> "cassandra.ap-northeast-1.amazonaws.com",
    CA_CENTRAL_1 -> "cassandra.ca-central-1.amazonaws.com",
    EU_CENTRAL_1 -> "cassandra.eu-central-1.amazonaws.com",
    EU_WEST_1 -> "cassandra.eu-west-1.amazonaws.com",
    EU_WEST_2 -> "cassandra.eu-west-2.amazonaws.com",
    EU_WEST_3 -> "cassandra.eu-west-3.amazonaws.com",
    EU_NORTH_1 -> "cassandra.eu-north-1.amazonaws.com",
    ME_SOUTH_1 -> "cassandra.me-south-1.amazonaws.com",
    SA_EAST_1 -> "cassandra.sa-east-1.amazonaws.com",
    US_GOV_EAST_1 -> "cassandra.us-gov-east-1.amazonaws.com",
    US_GOV_WEST_1 -> "cassandra.us-gov-west-1.amazonaws.com",
    CN_NORTH_1 -> "cassandra.cn-north-1.amazonaws.com.cn",
    CN_NORTHWEST_1 -> "cassandra.cn-northwest-1.amazonaws.com.cn"
  )

  private val endpoint = new InetSocketAddress(
    keyspacesEndpoints.getOrElse(
      region,
      sys.error(
        s"AWS Keyspaces is not available in $region. " +
        "See https://docs.aws.amazon.com/keyspaces/latest/devguide/programmatic.endpoints.html"
      )
    ),
    9142
  )
  // This is mutable, so needs to be a def to get a new one w/out prior settings.
  private def sessionBuilder: CqlSessionBuilder = CqlSession.builder
    .addContactPoint(endpoint)
    .withLocalDatacenter(region.id)
    .withMetricRegistry(metricRegistry.orNull)
    .withSslContext(SSLContext.getDefault)
    // TODO: support passing in key and secret explicitly, instead of getting from environment?
    .withAuthProvider(new SigV4AuthProvider(region.id))

  private def createQualifiedSession: CqlSession = sessionBuilder
    .withKeyspace(keyspace)
    .build

  // CREATE KEYSPACE IF NOT EXISTS `keyspace` WITH replication={'class':'SingleRegionStrategy'}
  private val createKeyspaceStatement: SimpleStatement =
    createKeyspace(keyspace).ifNotExists.withReplicationOptions(singletonMap("class", "SingleRegionStrategy")).build

  val session: CqlSession =
    try createQualifiedSession
    catch {
      case _: InvalidKeyspaceException if shouldCreateKeyspace =>
        val sess = sessionBuilder.build
        sess.execute(createKeyspaceStatement)
        // TODO: poll system_schema_mcs.keyspaces to wait for the keyspace to exist before proceeeding
        // https://docs.aws.amazon.com/keyspaces/latest/devguide/working-with-keyspaces.html#keyspaces-create
        sess.close()
        createQualifiedSession
    }

}
