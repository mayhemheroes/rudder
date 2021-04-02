module Init exposing (..)

import ApiCalls exposing (getAccountSettings)
import DataTypes exposing (..)
import List exposing (..)


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
      sections : List Section
      sections =
        [ Welcome
        , Account Default (AccountSettings "" "" "" Nothing Nothing Nothing)
        , Metrics Default NotDefined
        , GettingStarted Default
        ]

      initModel = Model flags.contextPath sections 0 False True True
    in
      ( initModel
      , getAccountSettings initModel
      )