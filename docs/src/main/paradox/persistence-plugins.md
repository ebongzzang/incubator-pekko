# Persistence Plugins 

Storage backends for journals and snapshot stores are pluggable in the Pekko persistence extension.

Plugins maintained within the Pekko organization are:

* [pekko-persistence-cassandra]($pekko.doc.dns$/docs/pekko-persistence-cassandra/current/) (no Durable State support)
* [pekko-persistence-jdbc]($pekko.doc.dns$/docs/pekko-persistence-jdbc/current/)
* [pekko-persistence-r2dbc]($pekko.doc.dns$/docs/pekko-persistence-r2dbc/current/)
* [pekko-persistence-dynamodb]($pekko.doc.dns$/docs/pekko-persistence-dynamodb/current/)

Plugins can be selected either by "default" for all persistent actors,
or "individually", when a persistent actor defines its own set of plugins.

When a persistent actor does NOT override the `journalPluginId` and `snapshotPluginId` methods,
the persistence extension will use the "default" journal, snapshot-store and durable-state plugins configured in `reference.conf`:

```
pekko.persistence.journal.plugin = ""
pekko.persistence.snapshot-store.plugin = ""
pekko.persistence.state.plugin = ""
```

However, these entries are provided as empty "", and require explicit user configuration via override in the user `application.conf`.

* For an example of a journal plugin which writes messages to LevelDB see @ref:[Local LevelDB journal](#local-leveldb-journal).
* For an example of a snapshot store plugin which writes snapshots as individual files to the local filesystem see @ref:[Local snapshot store](#local-snapshot-store).
* The state store is relatively new, one available implementation is the [pekko-persistence-jdbc-plugin]($pekko.doc.dns$/docs/pekko-persistence-jdbc/current/).

## Eager initialization of persistence plugin

By default, persistence plugins are started on-demand, as they are used. In some case, however, it might be beneficial
to start a certain plugin eagerly. In order to do that, you should first add `org.apache.pekko.persistence.Persistence`
under the `pekko.extensions` key. Then, specify the IDs of plugins you wish to start automatically under
`pekko.persistence.journal.auto-start-journals` and `pekko.persistence.snapshot-store.auto-start-snapshot-stores`.

For example, if you want eager initialization for the leveldb journal plugin and the local snapshot store plugin, your configuration should look like this:  

```
pekko {

  extensions = [org.apache.pekko.persistence.Persistence]

  persistence {

    journal {
      plugin = "pekko.persistence.journal.leveldb"
      auto-start-journals = ["org.apache.pekko.persistence.journal.leveldb"]
    }

    snapshot-store {
      plugin = "pekko.persistence.snapshot-store.local"
      auto-start-snapshot-stores = ["org.apache.pekko.persistence.snapshot-store.local"]
    }

  }

}
```

## Pre-packaged plugins

The Pekko Persistence module comes with few built-in persistence plugins, but none of these are suitable
for production usage in a Pekko Cluster. 

### Local LevelDB journal

This plugin writes events to a local LevelDB instance.

@@@ warning
The LevelDB plugin cannot be used in a Pekko Cluster since the storage is in a local file system.
@@@

The LevelDB journal is deprecated and it is not advised to build new applications with it.
As a replacement we recommend using [Pekko Persistence JDBC]($pekko.doc.dns$/docs/pekko-persistence-jdbc/current/index.html).

The LevelDB journal plugin config entry is `pekko.persistence.journal.leveldb`. Enable this plugin by
defining config property:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-plugin-config }

LevelDB based plugins will also require the following additional dependency declaration:

@@dependency[sbt,Maven,Gradle] {
  group="org.fusesource.leveldbjni"
  artifact="leveldbjni-all"
  version="1.8"
}

The default location of LevelDB files is a directory named `journal` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #journal-config }

With this plugin, each actor system runs its own private LevelDB instance.

One peculiarity of LevelDB is that the deletion operation does not remove messages from the journal, but adds
a "tombstone" for each deleted message instead. In the case of heavy journal usage, especially one including frequent
deletes, this may be an issue as users may find themselves dealing with continuously increasing journal sizes. To
this end, LevelDB offers a special journal compaction function that is exposed via the following configuration:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #compaction-intervals-config }

### Shared LevelDB journal

The LevelDB journal is deprecated and will be removed from a future Pekko version, it is not advised to build new 
applications with it. For testing in a multi node environment the "inmem" journal together with the @ref[proxy plugin](#persistence-plugin-proxy) can be used, but the actual journal used in production of applications is also a good choice.

@@@ note
This plugin has been supplanted by @ref:[Persistence Plugin Proxy](#persistence-plugin-proxy).
@@@

A shared LevelDB instance is started by instantiating the `SharedLeveldbStore` actor.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-creation }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-creation }

By default, the shared instance writes journaled messages to a local directory named `journal` in the current
working directory. The storage location can be changed by configuration:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-config }

Actor systems that use a shared LevelDB store must activate the `pekko.persistence.journal.leveldb-shared`
plugin.

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-journal-config }

This plugin must be initialized by injecting the (remote) `SharedLeveldbStore` actor reference. Injection is
done by calling the `SharedLeveldbJournal.setStore` method with the actor reference as argument.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-usage }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-usage }

Internal journal commands (sent by persistent actors) are buffered until injection completes. Injection is idempotent
i.e. only the first injection is used.

### Local snapshot store

This plugin writes snapshot files to the local filesystem.

@@@ warning
The local snapshot store plugin cannot be used in a Pekko Cluster since the storage is in a local file system.
@@@

The local snapshot store plugin config entry is `pekko.persistence.snapshot-store.local`. 
Enable this plugin by defining config property:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-snapshot-plugin-config }

The default storage location is a directory named `snapshots` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #snapshot-config }

Note that it is not mandatory to specify a snapshot store plugin. If you don't use snapshots
you don't have to configure it.

### Persistence Plugin Proxy

For testing purposes a persistence plugin proxy allows sharing of a journal and snapshot store on a single node across multiple 
actor systems (on the same or on different nodes). This, for example, allows persistent actors to failover to a backup 
node and continue using the shared journal instance from the backup node. The proxy works by forwarding all the 
journal/snapshot store messages to a single, shared, persistence plugin instance, and therefore supports any use case 
supported by the proxied plugin.

@@@ warning
A shared journal/snapshot store is a single point of failure and should only be used for testing
purposes.
@@@

The journal and snapshot store proxies are controlled via the `pekko.persistence.journal.proxy` and
`pekko.persistence.snapshot-store.proxy` configuration entries, respectively. Set the `target-journal-plugin` or
`target-snapshot-store-plugin` keys to the underlying plugin you wish to use (for example:
`pekko.persistence.journal.inmem`). The `start-target-journal` and `start-target-snapshot-store` keys should be
set to `on` in exactly one actor system - this is the system that will instantiate the shared persistence plugin.
Next, the proxy needs to be told how to find the shared plugin. This can be done by setting the `target-journal-address`
and `target-snapshot-store-address` configuration keys, or programmatically by calling the
`PersistencePluginProxy.setTargetLocation` method.

@@@ note
Pekko starts extensions lazily when they are required, and this includes the proxy. This means that in order for the
proxy to work, the persistence plugin on the target node must be instantiated. This can be done by instantiating the
`PersistencePluginProxyExtension` @ref:[extension](extending-pekko.md), or by calling the `PersistencePluginProxy.start` method.
@@@

@@@ note
The proxied persistence plugin can (and should) be configured using its original configuration keys.
@@@
