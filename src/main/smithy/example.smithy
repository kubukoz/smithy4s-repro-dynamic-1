namespace example

use smithy4s.api#simpleRestJson

@simpleRestJson
service ExampleService {
  operations: [ExampleOp]
}

@http(method:"GET", uri: "/greeting")
@readonly
operation ExampleOp {
  input: ExampleInput
}

structure ExampleInput {
  @required
  @httpHeader("X-GREETING")
  greeting: String
}
