package play.modules.benji

import java.net.URI

import com.zengularity.benji.ObjectStorage

/** Benji components contract */
trait BenjiComponents {
  /** The instance name (default: `default`) */
  def name: String

  /** The connection URI */
  def parsedUri: URI

  /** The ObjectStorage initialized according the current configuration */
  def benji: ObjectStorage
}

trait BenjiComponentsWithInjector extends BenjiComponents {
  /** The injector used to resolve the storage dependencies */
  def benjiInjector: com.zengularity.benji.spi.Injector

  final lazy val benji: ObjectStorage = {
    @SuppressWarnings(Array("TryGet"))
    def provider = BenjiModule.provider(parsedUri).get

    val p = provider

    p.injector = benjiInjector

    p.get
  }
}
