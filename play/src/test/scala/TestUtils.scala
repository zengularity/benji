package play.modules.benji

object TestUtils {
  def bindingKey(name: String): play.api.inject.BindingKey[com.zengularity.benji.ObjectStorage] = BenjiModule.key(name)
}
