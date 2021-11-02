module DataTypes exposing (..)

import Dict exposing (Dict)
import Either exposing (Either)
import File exposing (File)
import Http exposing (Error)
import MethodConditions exposing (..)
import Dom.DragDrop as DragDrop
import Time exposing (Posix)

--
-- All our data types
--

type alias TechniqueId = {value : String}

type alias MethodId = {value : String}

type alias CallId = {value : String}

type alias ParameterId = {value : String}

type alias Draft = { technique : Technique, origin : Maybe Technique, id : String, date : Posix}

type AgentValue = Value String | Variable (List AgentValue)

type Constraint =
    AllowEmpty Bool
  | AllowWhiteSpace Bool
  | MaxLength Int
  | MinLength Int
  | MatchRegex String
  | NotMatchRegex String
  | Select (List String)

type alias MethodParameter =
  { name        : ParameterId
  , description : String
  , type_       : String
  , constraints : List Constraint
  }

type Agent = Cfengine | Dsc

type alias Method =
  { id             : MethodId
  , name           : String
  , description    : String
  , classPrefix    : String
  , classParameter : ParameterId
  , agentSupport   : List Agent
  , parameters     : List MethodParameter
  , documentation  : Maybe String
  , deprecated     : Maybe String
  , rename         : Maybe String
  }

type alias Technique =
  { id          : TechniqueId
  , version     : String
  , name        : String
  , description : String
  , category    : String
  , elems       : List MethodElem
  , parameters  : List TechniqueParameter
  , resources   : List Resource
  }

type MethodElem = Call (Maybe CallId) MethodCall | Block (Maybe CallId) MethodBlock



type ReportingLogic = WorstReport | SumReport | FocusReport String

type alias MethodBlock =
  { id : CallId
  , component : String
  , condition : Condition
  , reportingLogic : ReportingLogic
  , calls : List MethodElem
  }

type alias MethodCall =
  { id         : CallId
  , methodName : MethodId
  , parameters : List CallParameter
  , condition  : Condition
  , component  : String
  , disableReporting : Bool
  }

type alias CallParameter =
  { id    : ParameterId
  , value : List AgentValue
  }

type alias TechniqueParameter =
  { id          : ParameterId
  , name        : String
  , description : String
  , mayBeEmpty  : Bool
  }

type alias TechniqueCategory =
    { id : String
    , name : String
    , path : String
    , subCategories : SubCategories
    }
type SubCategories = SubCategories (List TechniqueCategory)

allCategories t =
  let subElems = case t.subCategories of SubCategories l -> List.concatMap allCategories l
  in t :: subElems

allCategorieswithoutRoot m =
  let subElems = case m.categories.subCategories of SubCategories l -> List.concatMap allCategories l
  in subElems

type TechniqueState = Creation TechniqueId | Edit Technique | Clone Technique TechniqueId

type ModalState = DeletionValidation Technique

type DragElement = NewMethod Method | NewBlock | Move MethodElem

type DropElement = StartList | AfterElem (Maybe CallId) MethodElem | InBlock MethodBlock

type alias Model =
  { techniques         : List Technique
  , methods            : Dict String Method
  , categories         : TechniqueCategory
  , drafts             : Dict String Draft
  , mode               : Mode
  , contextPath        : String
  , techniqueFilter    : String
  , methodsUI          : MethodListUI
  , genericMethodsOpen : Bool
  , dnd                : DragDrop.State DragElement DropElement
  , modal              : Maybe ModalState
  , hasWriteRights     : Bool
  , dropTarget         : Maybe DropElement
  }

type ResourceState = New | Untouched | Deleted | Modified

type alias Resource =
  { name  : String
  , state : ResourceState
  }

type alias MethodListUI =
  { filter   : MethodFilter
  , docsOpen : List MethodId
  }

type alias MethodFilter =
  { name           : String
  , showDeprecated : Bool
  , agent          : Maybe Agent
  , state          : MethodFilterState
  }

type MethodFilterState = FilterOpened | FilterClosed
type ValidationState error = Unchanged | ValidState | InvalidState (List error)
type TechniqueNameError = EmptyName | AlreadyTakenName
type BlockError = EmptyComponent | NoFocusError
type TechniqueIdError = TooLongId | AlreadyTakenId | InvalidStartId
type MethodCallParamError = ConstraintError { id : ParameterId , message: String }

type alias MethodCallUiInfo =
  { mode       : MethodCallMode
  , tab        : MethodCallTab
  , validation : ValidationState MethodCallParamError
  }
type alias MethodBlockUiInfo =
  { mode       : MethodCallMode
  , tab        : MethodBlockTab
  , validation : ValidationState BlockError
  , showChildDetails : Bool
  }

type alias TechniqueUiInfo =
  { tab              : Tab
  , callsUI          : Dict String MethodCallUiInfo
  , blockUI          : Dict String MethodBlockUiInfo
  , openedParameters : List ParameterId
  , saving           : Bool
  , nameState        : ValidationState TechniqueNameError
  , idState          : ValidationState TechniqueIdError
  , enableDragDrop   : Maybe CallId
  }

type MethodCallTab = CallParameters | CallConditions | Result | CallReporting
type MethodBlockTab = BlockConditions | BlockReporting | Children
type MethodCallMode = Opened | Closed
type Tab = General |  Parameters | Resources | None
type Mode = Introduction | TechniqueDetails Technique TechniqueState TechniqueUiInfo



-- all events in the event loop
type Msg =
    SelectTechnique (Either Technique Draft)
  | GetTechniques   (Result Error (List Technique))
  | SaveTechnique   (Result Error Technique)
  | UpdateTechnique Technique
  | DeleteTechnique (Result Error TechniqueId)
  | GetTechniqueResources  (Result Error (List Resource))
  | GetCategories (Result Error  TechniqueCategory)
  | GetMethods   (Result Error (Dict String Method))
  | UIMethodAction CallId MethodCallUiInfo
  | UIBlockAction CallId MethodBlockUiInfo
  | RemoveMethod CallId
  | CloneElem  MethodElem CallId
  | MethodCallParameterModified MethodCall ParameterId String
  | MethodCallModified MethodElem
  | TechniqueParameterModified ParameterId TechniqueParameter
  | TechniqueParameterRemoved ParameterId
  | TechniqueParameterAdded ParameterId
  | TechniqueParameterToggle ParameterId
  | GenerateId (String -> Msg)
  | CallApi  (Model -> Cmd Msg)
  | SwitchTab Tab
  | UpdateTechniqueFilter String
  | UpdateMethodFilter MethodFilter
  | ToggleDoc MethodId
  | OpenMethods
  | OpenTechniques
  | NewTechnique TechniqueId
  | Ignore
  | AddMethod Method CallId
  | AddBlock CallId
  | SetCallId CallId
  | StartSaving
  | Copy String
  | GetDrafts (Dict String Draft)
  | CloneTechnique Technique TechniqueId
  | ResetTechnique
  | ResetMethodCall MethodElem
  | ToggleFilter
  | OpenDeletionPopup Technique
  | ClosePopup Msg
  | OpenFileManager
  | Export
  | StartImport
  | ImportFile File
  | ParseImportedFile File String
  | ScrollCategory String
  | MoveStarted DragElement
  | MoveTargetChanged DropElement
  | MoveCanceled
  | MoveCompleted DragElement DropElement
  | MoveFirstElemBLock MethodElem
  | SetMissingIds String
  | Notification (String -> Cmd Msg) String
  | DisableDragDrop
  | EnableDragDrop CallId

dragDropMessages : DragDrop.Messages Msg DragElement DropElement
dragDropMessages =
  { dragStarted = MoveStarted
  , dropTargetChanged = MoveTargetChanged
  , dragEnded = MoveCanceled
  , dropped = MoveCompleted
  }