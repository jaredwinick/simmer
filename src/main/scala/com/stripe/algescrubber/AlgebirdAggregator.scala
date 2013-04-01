package com.stripe.algescrubber

import com.twitter.algebird._
import com.twitter.bijection._
import com.twitter.chill._

object AlgebirdAggregators extends Registrar {
	register("hll", 12){new HyperLogLog(_)}
	register("mh", 64){new MinHash(_)}
	register("top", 10){new Top(_)}
}

trait AlgebirdAggregator[A] extends Aggregator[A] {
	val MAGIC = "%%%"

	def semigroup : Semigroup[A]
	def injection : Injection[A, String]

	def serialize(value : A) = MAGIC + injection(value)
	def deserialize(serialized : String) = {
		if(serialized.startsWith(MAGIC))
			injection.invert(serialized.drop(MAGIC.size))
		else
			None
	}

	def reduce(left : A, right : A) = semigroup.plus(left, right)
}

trait KryoAggregator[A] extends AlgebirdAggregator[A] {
  val injection : Injection[A,String] = 
  	KryoInjection.asInstanceOf[Injection[A, Array[Byte]]] andThen
  	Bijection.bytes2Base64 andThen
  	Base64String.unwrap
}

class HyperLogLog(size : Int) extends KryoAggregator[HLL] {
  val semigroup = new HyperLogLogMonoid(size)
  def prepare(in : String) = semigroup.create(in.getBytes)
  def present(out : HLL) = out.estimatedSize.toInt.toString
}

class MinHash(hashes : Int) extends AlgebirdAggregator[Array[Byte]] {
	val semigroup = new MinHasher16(0.1, hashes * 2)
	val injection = Bijection.bytes2Base64 andThen Base64String.unwrap
	def prepare(in : String) = semigroup.init(in)
	def present(out : Array[Byte]) = {
		out.grouped(2).toList.map{h => h.map{"%02X".format(_)}.mkString}.mkString(":")
	}
}

class Top(k : Int) extends KryoAggregator[TopK[(Double,String)]] {
	val semigroup = new TopKMonoid[(Double,String)](k)
	def prepare(in : String) = {
		val (score, item) = Main.split(in, ":").get
		semigroup.build((score.toDouble*-1, item))
	}

	def present(out : TopK[(Double,String)]) = {
		out.items.map{case (score,item) => (score * -1).toString + ":" + item}.mkString(",")
	}
}