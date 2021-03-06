import org.apache.spark._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import scala.xml.{XML, NodeSeq}

object PageRank{
	def main(args: Array[String]){
		val startTime=System.currentTimeMillis
		val inputFile=args(0)
		val outputFile=args(1)
		val checkFile=args(2)
		val numIterations=args(3).toInt
		val conf=new SparkConf().setAppName("PageRank")

		val sc=new SparkContext(conf)

		val input=sc.textFile(inputFile)
		val college_list=sc.textFile(checkFile)

		//parse the wikipedia page data into a graph
		//generate vertex table

		val n=college_list.count()
		val defaultRank=1.0/n

		def pageHash(title: String): VertexId={
			title.toLowerCase.replace(" ","").hashCode.toLong
		}

		def parseArticle(line: String):(String, Array[String])={
			val fields=line.split("\t")
			val (title, body)=(fields(1),fields(3).replace("\\n","\n"))
			val id=new String(title)
			val links=
				if(body=="\\N"){
					NodeSeq.Empty
				}else{
					try{
						XML.loadString(body) \\ "link" \ "target"
					}catch{
						case e: org.xml.sax.SAXParseException=>
							System.err.println("parse error!")
						NodeSeq.Empty
					}
				}
			val outEdges=links.map(link=>new String(link.text)).toArray

			(id, outEdges)//(String, Array(String))
		}
		val links=input.map(parseArticle _)


		val vertexTable:RDD[(VertexId, String)]=college_list.map(line=>{
			(pageHash(line),line)
		}).cache

		val edgeTable:RDD[Edge[Double]]=links.flatMap(line=>{
			val srcVid=pageHash(line._1)
			
			line._2.iterator.map(x=>Edge(srcVid, pageHash(x), defaultRank))
			// sc.parallelize(line._2.foreach(x=>Edge(srcVid, pageHash(x), 1.0)))
		}).cache
		
		val graph=Graph(vertexTable, edgeTable, "").subgraph(vpred={(v,d)=>d.nonEmpty}).cache
		val prGraph=graph.staticPageRank(numIterations).cache
		val titleAndPrGraph=graph.outerJoinVertices(prGraph.vertices){
			(v, title, rank)=>(rank.getOrElse(0.0), title)
		}
		
		val result=titleAndPrGraph.vertices.top(100){
			Ordering.by((entry:(VertexId, (Double, String)))=>entry._2._1)
		}
		val time=(System.currentTimeMillis-startTime)/1000.0
		sc.parallelize(result).map(t=>t._2._2+": "+t._2._1).saveAsTextFile(outputFile)
		result.foreach(t=>println(t._2._2+": "+t._2._1))
		//val time=(System.currentTimeMillis-startTime)/1000.0
		println("Completed %d iterations in %f seconds: %f seconds per iteration".format(numIterations,time, time/numIterations))
		System.exit(0)
	}
}
